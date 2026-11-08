package com.ibrahimdans.i18n.plugin.ide.uiflows

import com.ibrahimdans.i18n.plugin.ide.screenshots.ScreenshotHarness
import com.ibrahimdans.i18n.plugin.ide.toolwindow.I18nToolWindowPanel
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.elements.table
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.minutes

/**
 * The plugin in an IDE without the JavaScript plugin — IntelliJ IDEA Community, which needs no
 * license — on examples/simple: the dependency is optional (#883), and the tool window must still
 * show the translation files' keys. The Plugin Verifier proves that every registered class loads;
 * this proves that what loads works.
 */
class CommunityFlowsTest {

    private val outputDirectory: Path = Path.of(System.getProperty("i18n.ui.flows.output"))

    @Test
    fun theToolWindowListsTheTranslationFilesKeysWithoutJavaScript() {
        assertFalse(GraphicsEnvironment.isHeadless(), "UI flows require a display; use xvfb-run on Linux.")
        val project = copyExample(outputDirectory.resolve("projects").resolve("community"))
        val failure = outputDirectory.resolve("failed-community.png")
        Files.deleteIfExists(failure)
        ScreenshotHarness.withIde("flow-community", project, community = true) {
            try {
                driver.openToolWindow("I18n")
                val panel = frame.x { byType(I18nToolWindowPanel::class.java) }.waitFound(1.minutes)
                panel.x { byVisibleText("Table") }.waitFound(1.minutes).click()
                // A key of asset/en/sample.json, under the `./asset/` default namespace the example's settings name.
                panel.table().waitOneText(timeout = 2.minutes) { it.text.contains("Key41") }
            } catch (e: Throwable) {
                ScreenshotHarness.captureScreen(failure)
                throw AssertionError("community: ${e.message}; screen in $failure", e)
            }
        }
    }

    /**
     * A copy of the example, configured as the example's own (unversioned) `.idea/i18nSettings.xml` is:
     * the translation files of `asset/`, JSON first, and no setup wizard on opening.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun copyExample(target: Path): Path {
        if (Files.exists(target)) target.deleteRecursively()
        val source = ScreenshotHarness.projectSource.toFile()
        check(source.resolve("asset/en/sample.json").isFile) { "$source is not examples/simple" }
        check(source.copyRecursively(target.toFile(), overwrite = true)) { "Could not copy $source into $target" }
        val project = target.toAbsolutePath().normalize()
        ScreenshotHarness.writePluginSettings(
            project,
            mapOf("defaultNs" to "./asset/", "foldingEnabled" to "true", "preferredLocalization" to "json", "wizardDismissed" to "true"),
        )
        return project
    }
}
