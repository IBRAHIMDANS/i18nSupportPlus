package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.driver.sdk.closeAllNotifications
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.ImageIO
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

/**
 * The first reproducible README capture. It intentionally captures only the plugin panel, whose
 * bounds Driver reads from Swing, rather than using fixed screen coordinates or an external crop.
 */
class ToolWindowScreenshotTest {

    @Test
    fun tableScreenshotIsRepeatable() {
        assertFalse(GraphicsEnvironment.isHeadless(), "UI screenshots require a display; use xvfb-run on Linux.")
        outputDirectory.toFile().deleteRecursively()
        Files.createDirectories(outputDirectory)

        val first = captureTable("first")
        val second = captureTable("second")
        assertSamePixels(first, second, "Two fresh WebStorm sessions rendered different table screenshots")

        when (mode) {
            ScreenshotMode.RECORD -> Files.copy(first, baseline, REPLACE_EXISTING)
            ScreenshotMode.VERIFY -> assertSamePixels(baseline, first, "The README screenshot is out of date")
        }
    }

    private fun captureTable(runName: String): Path {
        val project = copyReferenceProject(runName)
        val destination = outputDirectory.resolve(runName).resolve(FILE_NAME)
        val licenseKey = requireNotNull(System.getenv("LICENSE_KEY")?.takeIf(String::isNotBlank)) {
            "LICENSE_KEY is required to run licensed WebStorm UI screenshots"
        }

        Starter.newContext(
            "toolwindow-table-$runName",
            TestCase(IdeProductProvider.WS, LocalProjectInfo(project)).withVersion(ideVersion)
        ).apply {
            applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", "true")
                addSystemProperty("ide.show.tips.on.startup.default.value", "false")
                addSystemProperty("jb.consents.confirmation.enabled", "false")
                addSystemProperty("jb.privacy.policy.text", "<!--999.999-->")
                addSystemProperty("user.language", "en")
                addSystemProperty("user.country", "US")
                addSystemProperty("sun.java2d.uiScale", "1")
            }
            setLicense(licenseKey)
            PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("path.to.build.plugin")))
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            // Balloons open over the bottom-right corner, where the tool window is docked; the
            // copied project has no node_modules, so "Install dependencies" is sure to show.
            closeAndDisableAllBalloonNotifications()
            ideFrame {
                // Without a window manager under Xvfb the frame keeps its default size, which
                // would make the panel width depend on the IDE build rather than on the test.
                setBounds(FRAME_BOUNDS)
                waitFor("the IDE frame to take its fixed bounds", 1.minutes) { boundsOnScreen == FRAME_BOUNDS }
                // Through the project's ToolWindowManager: the ActivateI18nToolWindow action is
                // disabled when Driver invokes it, its data context carries no project.
                openToolWindow("I18n")
                val toolWindow = x { byType(I18nToolWindowPanel::class.java) }.waitFound(1.minutes)
                toolWindow.x { byVisibleText("Table") }.waitFound(1.minutes).click()
                val table = toolWindow.x { byType("com.intellij.ui.table.JBTable") }.waitFound(1.minutes)
                waitFor("the Table view to become visible", 1.minutes) { table.component.isShowing() }
                table.waitOneText("actions.eddit", timeout = 1.minutes)
                toolWindow.waitOneText("Usage (not counted)", timeout = 1.minutes)
                // Some balloons ("Install dependencies") show up after indexing: close them again
                // once the table is ready, right before the capture.
                closeAllNotifications()
                // The search field takes the focus when the view opens and its caret blinks, so two
                // captures would differ by a cursor. The table has no caret.
                table.setFocus()
                waitFor("the table to own the focus", 1.minutes) { table.isFocusOwner() }
                capture(toolWindow.boundsOnScreen, destination)
            }
        }

        return destination
    }

    private fun copyReferenceProject(runName: String): Path {
        val target = outputDirectory.resolve("projects").resolve(runName).absolute().normalize()
        val root = outputDirectory.resolve("projects").absolute().normalize()
        require(target.startsWith(root)) { "Refusing to replace a project outside the screenshot work directory" }
        target.toFile().deleteRecursively()
        Files.createDirectories(target)

        for (entry in REFERENCE_PROJECT_ENTRIES) {
            val source = projectSource.resolve(entry).toFile()
            check(source.exists()) { "Reference project is missing $entry" }
            check(source.copyRecursively(target.resolve(entry).toFile(), overwrite = true)) {
                "Could not copy $entry into the screenshot project"
            }
        }
        // The reference workspace contains tabs and machine-local history. Configuration is enough.
        target.resolve(".idea/workspace.xml").toFile().delete()
        // The copy sits inside this repository's build/ directory: its VCS mapping would point to a
        // directory that is not a root and raise an "Invalid VCS root mapping" balloon.
        target.resolve(".idea/vcs.xml").toFile().delete()
        return target
    }

    private fun capture(bounds: Rectangle, destination: Path) {
        assertTrue(bounds.width > 0 && bounds.height > 0, "The tool window panel has no visible bounds")
        Files.createDirectories(destination.parent)
        val robot = Robot()
        // Driver's setFocus leaves the pointer over a table row: hover highlight and tooltip.
        // Park it over the empty editor area, above the tool window.
        robot.mouseMove(FRAME_BOUNDS.centerX.toInt(), FRAME_BOUNDS.y + bounds.y / 2)
        robot.waitForIdle()
        assertTrue(ImageIO.write(stableCapture(robot, bounds), "png", destination.toFile()))
    }

    /** Waits for the panel to stop changing (tooltip fading out, repaint after the pointer left). */
    private fun stableCapture(robot: Robot, bounds: Rectangle): BufferedImage {
        var previous = robot.createScreenCapture(bounds)
        repeat(STABLE_CAPTURE_ATTEMPTS) {
            robot.delay(STABLE_CAPTURE_INTERVAL_MS)
            val current = robot.createScreenCapture(bounds)
            if (samePixels(previous, current)) return current
            previous = current
        }
        error("The tool window kept changing for ${STABLE_CAPTURE_ATTEMPTS * STABLE_CAPTURE_INTERVAL_MS} ms")
    }

    private fun samePixels(a: BufferedImage, b: BufferedImage): Boolean =
        a.width == b.width && a.height == b.height &&
            (0 until a.height).all { y -> (0 until a.width).all { x -> a.getRGB(x, y) == b.getRGB(x, y) } }

    private fun assertSamePixels(expected: Path, actual: Path, message: String) {
        check(expected.exists()) { "Missing screenshot: $expected" }
        check(actual.exists()) { "Missing screenshot: $actual" }
        val expectedImage = ImageIO.read(expected.toFile())
        val actualImage = ImageIO.read(actual.toFile())
        assertEquals(expectedImage.width, actualImage.width, "$message: different widths")
        assertEquals(expectedImage.height, actualImage.height, "$message: different heights")

        for (y in 0 until expectedImage.height) {
            for (x in 0 until expectedImage.width) {
                assertEquals(expectedImage.getRGB(x, y), actualImage.getRGB(x, y), "$message at ($x, $y)")
            }
        }
    }

    private enum class ScreenshotMode { RECORD, VERIFY }

    private companion object {
        const val FILE_NAME = "toolwindow-table.png"
        // The whole 1920x1440 Xvfb screen the recipe starts.
        val FRAME_BOUNDS = Rectangle(0, 0, 1920, 1440)
        const val STABLE_CAPTURE_ATTEMPTS = 20
        const val STABLE_CAPTURE_INTERVAL_MS = 500
        val ideVersion: String = System.getProperty("i18n.ui.screenshots.ideVersion")
        val mode: ScreenshotMode = ScreenshotMode.valueOf(System.getProperty("i18n.ui.screenshots.mode").uppercase())
        val baseline: Path = Path.of(System.getProperty("i18n.ui.screenshots.baseline"))
        val outputDirectory: Path = Path.of(System.getProperty("i18n.ui.screenshots.output"))
        val projectSource: Path = Path.of(System.getProperty("i18n.ui.screenshots.projectSource"))
        val REFERENCE_PROJECT_ENTRIES = listOf(".idea", "package.json", "public", "src")
    }
}
