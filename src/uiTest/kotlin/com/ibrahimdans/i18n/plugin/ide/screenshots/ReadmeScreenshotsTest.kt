package com.ibrahimdans.i18n.plugin.ide.screenshots

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.ImageIO
import kotlin.io.path.exists

/**
 * The README images, taken twice in fresh WebStorm sessions. Each image must come out identical
 * in both runs; then RECORD copies it into docs/img and VERIFY compares it with the committed one.
 */
class ReadmeScreenshotsTest {

    @Test
    fun readmeScreenshotsAreRepeatable() {
        assertFalse(GraphicsEnvironment.isHeadless(), "UI screenshots require a display; use xvfb-run on Linux.")
        outputDirectory.toFile().deleteRecursively()
        Files.createDirectories(outputDirectory)

        val selected = { screenshot: Screenshot -> only.isEmpty() || screenshot.fileName in only }
        val taken = SESSIONS.flatMap { it.screenshots }.filter(selected).map { it.fileName }
        assertTrue(taken.isNotEmpty(), "No screenshot matches $only")
        val failures = RUNS.flatMap { runName ->
            SESSIONS.flatMap { session -> ScreenshotHarness.run(session, runName, outputDirectory, selected) }
        }
        // An image both runs took identically is recorded even when another scenario failed: a long
        // run is not thrown away for one image still being worked on.
        val problems = failures + taken.mapNotNull { fileName ->
            val (first, second) = RUNS.map { outputDirectory.resolve(it).resolve(fileName) }
            if (!first.exists() || !second.exists()) return@mapNotNull null // already among the failures
            difference(first, second)?.let { return@mapNotNull "$fileName: two fresh sessions differ, $it" }
            when (mode) {
                ScreenshotMode.RECORD -> {
                    Files.copy(first, baselineDirectory.resolve(fileName), REPLACE_EXISTING)
                    null
                }
                ScreenshotMode.VERIFY -> difference(baselineDirectory.resolve(fileName), first)
                    ?.let { "$fileName: out of date, $it" }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n", "Screenshots failed:\n"))
    }

    private fun difference(expected: Path, actual: Path): String? {
        if (!expected.exists()) return "missing $expected"
        if (!actual.exists()) return "missing $actual"
        return ScreenshotHarness.firstDifference(ImageIO.read(expected.toFile()), ImageIO.read(actual.toFile()))
    }

    private enum class ScreenshotMode { RECORD, VERIFY }

    private companion object {
        val RUNS = listOf("first", "second")
        val SESSIONS = listOf(
            ToolWindowScreenshots.SESSION,
            SettingsScreenshots.SESSION,
            EditorScreenshots.SESSION,
            EditorScreenshots.FOLDING_SESSION,
            WizardScreenshots.SESSION,
        )
        val mode: ScreenshotMode = ScreenshotMode.valueOf(System.getProperty("i18n.ui.screenshots.mode").uppercase())
        val baselineDirectory: Path = Path.of(System.getProperty("i18n.ui.screenshots.baselineDirectory"))
        val outputDirectory: Path = Path.of(System.getProperty("i18n.ui.screenshots.output"))
        // Comma-separated file names, to iterate on a few images without taking them all.
        val only: Set<String> = System.getProperty("i18n.ui.screenshots.only").orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    }
}
