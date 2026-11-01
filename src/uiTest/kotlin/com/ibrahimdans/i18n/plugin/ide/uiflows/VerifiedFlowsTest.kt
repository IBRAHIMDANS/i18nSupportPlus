package com.ibrahimdans.i18n.plugin.ide.uiflows

import com.ibrahimdans.i18n.plugin.ide.screenshots.EditorScreenshots.NAVIGATION
import com.ibrahimdans.i18n.plugin.ide.screenshots.EditorScreenshots.navigation
import com.ibrahimdans.i18n.plugin.ide.screenshots.EditorScreenshots.openEditor
import com.ibrahimdans.i18n.plugin.ide.screenshots.ScreenshotHarness
import com.ibrahimdans.i18n.plugin.ide.screenshots.ScreenshotHarness.dismissIdePrompts
import com.ibrahimdans.i18n.plugin.ide.screenshots.ScreenshotScope
import com.ibrahimdans.i18n.plugin.ide.screenshots.ScreenshotSession
import com.ibrahimdans.i18n.plugin.ide.screenshots.fieldRightOf
import com.ibrahimdans.i18n.plugin.ide.screenshots.typeOver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

/**
 * User flows played in a fresh WebStorm on examples/react-multi-namespace, each checked by its
 * effect: what the editor shows and what the project stores, not how the screen looks.
 */
class VerifiedFlowsTest {

    @Test
    fun setupWizardStoresTheDetectedConfiguration() = flow("wizard", pluginSettings = null) { project ->
        // The frame as context: without a project in it, the action is disabled.
        driver.invokeAction("com.ibrahimdans.i18n.RunSetupWizard", now = false, component = frame.component)
        val wizard = driver.ui.dialog().also { it.waitFound(1.minutes) }
        wizard.pressButton("Next")
        wizard.waitOneText("Step 2 — Translation Files Found", timeout = 1.minutes)
        wizard.pressButton("Next")
        wizard.waitOneText("Translations root:", timeout = 1.minutes)
        wizard.pressButton("Create")
        waitFor("the wizard to close", 1.minutes) { !driver.ui.isDialogOpened() }
        waitForStored(project, "translationsRoot" to "public/locales", "defaultNs" to "common")
    }

    @Test
    fun showTranslationsInlineFoldsAndUnfoldsTheOpenEditor() = flow("inline") { project ->
        val editor = openEditor(NAVIGATION, navigation(LINE_15_KEY))
        // Out of every key: the caret inside one unfolds it.
        editor.moveCaretToOffset(0)
        check(foldedValues(editor).none { it in MENU_VALUES }) { "Keys folded before Show Translations Inline" }

        toggleInline(editor)
        waitFor("the menu keys to show their English values", 1.minutes) { foldedValues(editor).containsAll(MENU_VALUES) }
        waitForStored(project, "foldingEnabled" to "true")

        toggleInline(editor)
        waitFor("the menu keys to show again", 1.minutes) { foldedValues(editor).none { it in MENU_VALUES } }
        waitForStored(project, "foldingEnabled" to "false")
    }

    @Test
    fun excludedExtensionSetInSettingsSilencesTheEditor() = flow("settings") { project ->
        val editor = openEditor(NAVIGATION, navigation(LINE_15_KEY, misspelledProfile = true))
        waitFor("navigation:menu.profil to be reported", 1.minutes) { unresolvedKeys(editor) > 0 }

        driver.invokeAction("ShowSettings", now = false, component = frame.component)
        val dialog = frame.settingsDialog().also { it.waitFound(1.minutes) }
        // Tall enough for Excluded file extensions to show without scrolling.
        dialog.setBounds(Rectangle(0, 0, 1400, 1400))
        dialog.openTreeSettingsSection("Tools", "i18n Support Plus")
        dialog.content().fieldRightOf("Excluded file extensions").typeOver("tsx")
        dialog.pressButton("OK")
        waitFor("the settings to close", 1.minutes) { !driver.ui.isDialogOpened() }

        waitFor("Navigation.tsx to lose its i18n annotations", 1.minutes) { unresolvedKeys(editor) == 0 }
        waitForStored(project, "excludedFileExtensions" to "tsx")
    }

    /**
     * Opens a copy of the reference project with [pluginSettings], runs [steps] on it, and keeps
     * the screen as it was when a step failed.
     */
    private fun flow(
        name: String,
        pluginSettings: Map<String, String>? = ScreenshotSession.REFERENCE_SETTINGS,
        steps: ScreenshotScope.(project: Path) -> Unit,
    ) {
        assertFalse(GraphicsEnvironment.isHeadless(), "UI flows require a display; use xvfb-run on Linux.")
        val project = ScreenshotHarness.prepareProject(outputDirectory.resolve("projects").resolve(name), pluginSettings)
        val failure = outputDirectory.resolve("failed-$name.png")
        Files.deleteIfExists(failure)
        ScreenshotHarness.withIde("flow-$name", project) {
            try {
                driver.dismissIdePrompts()
                steps(project)
            } catch (e: Throwable) {
                ScreenshotHarness.captureScreen(failure)
                throw AssertionError("$name: ${e.message}; screen in $failure", e)
            }
        }
    }

    private fun ScreenshotScope.toggleInline(editor: JEditorUiComponent) {
        driver.invokeAction("com.ibrahimdans.i18n.ToggleFolding", now = false, component = editor.component)
    }

    /** The placeholders of the collapsed fold regions: the values Show Translations Inline shows. */
    private fun ScreenshotScope.foldedValues(editor: JEditorUiComponent): List<String> =
        driver.withContext(OnDispatcher.EDT) {
            cast(editor.editor, FoldingEditor::class).getFoldingModel().getAllFoldRegions()
                .filterNot { it.isExpanded() }
                .map { it.getPlaceholderText() }
        }

    private fun unresolvedKeys(editor: JEditorUiComponent): Int =
        editor.getAllHighlights().count {
            // Some highlights carry no description: the remote call then returns null.
            val description: String? = it.getDescription()
            description == "Unresolved key"
        }

    /**
     * Saves the project and waits for [options] in its `.idea/i18nSettings.xml`: the plugin's
     * settings are written on save, not when they change.
     */
    private fun ScreenshotScope.waitForStored(project: Path, vararg options: Pair<String, String>) {
        val file = project.resolve(".idea/i18nSettings.xml")
        waitFor("${options.toList()} in $file", 1.minutes) {
            driver.invokeAction("SaveAll", now = true, component = frame.component)
            file.exists() && storedOptions(file).entries.containsAll(options.toMap().entries)
        }
    }

    /**
     * The options of the file. An option left at its default value is not written: false is then
     * read from its absence, as the plugin does.
     */
    private fun storedOptions(file: Path): Map<String, String> {
        val written = OPTION.findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2] }
        return mapOf("foldingEnabled" to "false") + written
    }

    private companion object {
        val outputDirectory: Path = Path.of(System.getProperty("i18n.ui.flows.output"))
        val OPTION = Regex("""<option name="([^"]+)" value="([^"]*)"""")
        // The key on line 15 of the example as committed, which resolves.
        const val LINE_15_KEY = "navigation:breadrumb.home"
        // The English values of the four menu links of Navigation.tsx.
        val MENU_VALUES = listOf("Home", "Dashboard", "Profile", "Settings")
    }
}

@Remote("com.intellij.openapi.editor.Editor")
interface FoldingEditor {
    fun getFoldingModel(): FoldingModel
}

@Remote("com.intellij.openapi.editor.FoldingModel")
interface FoldingModel {
    fun getAllFoldRegions(): Array<FoldRegion>
}

@Remote("com.intellij.openapi.editor.FoldRegion")
interface FoldRegion {
    fun getPlaceholderText(): String
    fun isExpanded(): Boolean
}
