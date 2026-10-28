package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.gutter
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * The editor images of the README, on examples/react-multi-namespace. Each screenshot sets the text
 * of its file from the reference copy, so it never depends on what an earlier one typed.
 */
object EditorScreenshots {

    private const val NAVIGATION = "src/components/Navigation.tsx"
    private const val DASHBOARD = "src/components/Dashboard.tsx"
    // Navigation.tsx, line 15: the key the annotation, gutter and completion images play with.
    private const val LINE_15_KEY = "navigation:breadrumb.home"

    private fun reference(path: String): String = Files.readString(ScreenshotHarness.projectSource.resolve(path))

    /** Navigation.tsx with [key] on line 15, and menu.profile misspelled for a red badge on line 19. */
    private fun navigation(key: String, misspelledProfile: Boolean = false): String {
        val text = reference(NAVIGATION).replace("t('$LINE_15_KEY')", "t('$key')")
        return if (misspelledProfile) text.replace("navigation:menu.profile", "navigation:menu.profil") else text
    }

    val SESSION = ScreenshotSession(
        "editor",
        listOf(
            Screenshot("annotation-resolved.png") {
                val editor = openEditor(NAVIGATION, navigation("navigation:breadcrumb.home"))
                editor.caretAtEndOf("navigation:breadcrumb.home')} /")
                lines(editor, 13, 15, width = 900)
            },
            Screenshot("annotation-unresolved.png") {
                val editor = openEditor(NAVIGATION, navigation("navigation:breadcrumb.homme"))
                editor.caretAtEndOf("navigation:breadcrumb.homme')} /")
                lines(editor, 13, 15, width = 900)
            },
            Screenshot("gutter-icons.png") {
                val editor = openEditor(NAVIGATION, navigation("common:actions.saxve", misspelledProfile = true))
                editor.caretAtEndOf("common:actions.saxve')} /")
                lines(editor, 11, 28, width = 1300)
            },
            Screenshot("gutter-icons-tooltip.png", keepPointer = true) {
                val editor = openEditor(NAVIGATION, navigation("common:actions.saxve", misspelledProfile = true))
                editor.caretAtEndOf("common:actions.saxve')} /")
                frame.gutter().hoverOverIcon(15)
                waitForText("the gutter badge tooltip") { it.startsWith("i18n:") }
                lines(editor, 11, 20, width = 1300)
            },
            Screenshot("hover-hint.png", cleanUp = { driver.ui.keyboard { escape() } }) {
                val editor = openEditor(NAVIGATION, navigation("common:actions.saxve", misspelledProfile = true))
                // The popup a hover opens, asked for at the caret: a pointer hover depends on timing.
                editor.caretAtEndOf("'navigation:menu.ho")
                editorAction(editor, "QuickJavaDoc")
                waitForText("the translations hint") { it == "Accueil" }
                lines(editor, 14, 23, width = 1400)
            },
            Screenshot("inlay-hints.png") {
                val editor = openEditor(NAVIGATION, navigation("common:actions.saxve", misspelledProfile = true))
                editor.caretAtEndOf("common:actions.saxve')} /")
                val call = "{t('navigation:menu.home')}"
                val start = editor.text.indexOf(call)
                val line = lines(editor, 17, 17, width = 1)
                val left = editor.boundsOnScreen.x + editor.interact { offsetToXY(start) }.x - INLAY_MARGIN
                val right = editor.boundsOnScreen.x + editor.interact { offsetToXY(start + call.length) }.x + INLAY_MARGIN
                Rectangle(left, line.y, right - left, line.height)
            },
            Screenshot("completion.png", cleanUp = { driver.ui.keyboard { escape() } }) {
                val editor = openEditor(NAVIGATION, navigation("common:actions."))
                editor.caretAtEndOf("common:actions.")
                editorAction(editor, "CodeCompletion")
                waitForText("the key completion") { it.contains("Enter to insert") }
                lines(editor, 15, 24, width = 900)
            },
            Screenshot("annotation-missing-file.png", cleanUp = { driver.ui.keyboard { escape() } }) {
                val editor = openEditor(NAVIGATION, navigation("settings:title"))
                // The file fixes sit on the namespace part of the key, the one no file holds.
                editor.caretAtEndOf("t('sett")
                editorAction(editor, "ShowIntentionActions")
                waitForText("the quick fixes") { it == "Create JSON translation files" }
                listShowing("Create JSON translation files").boundsOnScreen
            },
            Screenshot(
                "extraction-translation-value.png",
                cleanUp = { if (driver.ui.isDialogOpened()) driver.ui.dialog().pressButton("Cancel") },
            ) {
                val editor = openEditor(NAVIGATION, navigation("common:actions.archive"))
                editor.caretAtEndOf("common:actions.arch")
                editorAction(editor, "ShowIntentionActions")
                waitForText("the quick fixes") { it == "Create i18n key" }
                editor.keyboard {
                    typeText("Create i18n key")
                    enter()
                }
                // A Messages input dialog: its title is not a component attribute, its prompt is.
                lateinit var dialog: UiComponent
                waitFor("the Translation Value dialog", 1.minutes) {
                    driver.ui.xx("//div[@class='MyDialog']").list()
                        .firstOrNull { it.hasSubtext("Enter translation value for") }?.also { dialog = it } != null
                }
                dialog.boundsOnScreen
            },
            Screenshot("navigation.png") {
                val editor = openEditor(NAVIGATION, navigation("common:actions.saxve", misspelledProfile = true))
                editor.caretAtEndOf("'navigation:menu.ho")
                editorAction(editor, "GotoDeclaration")
                // en and fr both declare the key: the IDE asks which file to open.
                if (runCatching { waitForText("the declaration chooser") { it.endsWith("navigation.json") } }.isSuccess) {
                    driver.ui.keyboard { enter() }
                }
                val json = waitForEditor("navigation.json")
                val tabs = frame.x("//div[@class='EditorTabs']").waitFound(1.minutes)
                val bottom = lines(json, 1, 11, width = 1).let { it.y + it.height }
                Rectangle(tabs.boundsOnScreen.x, tabs.boundsOnScreen.y, 760, bottom - tabs.boundsOnScreen.y)
            },
            Screenshot("extraction-intent.png", cleanUp = { driver.ui.keyboard { escape() } }) {
                val editor = openEditor(DASHBOARD, reference(DASHBOARD))
                editor.caretAtEndOf("<h3>Actions (from")
                editorAction(editor, "ShowIntentionActions")
                waitForText("the intentions") { it == "Extract i18n key" }
                lines(editor, 47, 58, width = 1000)
            },
            Screenshot("extraction.png") {
                extractionDialog().boundsOnScreen
            },
            Screenshot(
                "extraction-add-namespace.png",
                cleanUp = {
                    driver.ui.keyboard { escape() }
                    if (driver.ui.isDialogOpened()) driver.ui.dialog().pressButton("Cancel")
                },
            ) {
                val dialog = if (driver.ui.isDialogOpened()) driver.ui.dialog() else extractionDialog()
                dialog.x { byVisibleText("+") }.click()
                driver.ui.x { byVisibleText("Namespace name (letters, digits, hyphens):") }.waitFound(1.minutes)
                dialog.boundsOnScreen
            },
        ),
    )

    /** *Show Translations Inline* on from the start: open editors are not folded when it is switched on (TASK-INLINE-TOGGLE-OPEN-EDITORS). */
    val FOLDING_SESSION = ScreenshotSession(
        "folding",
        listOf(
            Screenshot("folding-before.png") {
                val editor = openEditor(NAVIGATION, reference(NAVIGATION))
                editor.caretAtEndOf("import { useTranslation }")
                lines(editor, 19, 21, width = 1220)
            },
            Screenshot("folding-after.png") {
                val editor = openEditor(NAVIGATION, reference(NAVIGATION))
                // The caret inside a key unfolds it, as a user clicking it does.
                editor.caretAtEndOf("{t('navigation:menu.set")
                lines(editor, 19, 20, width = 1430)
            },
        ),
        pluginSettings = ScreenshotSession.REFERENCE_SETTINGS + ("foldingEnabled" to "true"),
    )

    private const val INLAY_MARGIN = 6

    /** Opens [path] with [text] as its content and waits for the plugin's highlighting of it. */
    private fun ScreenshotScope.openEditor(path: String, text: String): JEditorUiComponent {
        driver.openFile(path)
        val editor = waitForEditor(path.substringAfterLast('/'))
        if (editor.text != text) editor.text = text
        driver.waitForCodeAnalysis(file = editor.editor.getVirtualFile())
        // TypeScript reads node_modules after the IDE's own indexing: until then every JSX tag is
        // an error of type 'any', underlined in red on the capture.
        waitFor("TypeScript to load the example's dependencies", 3.minutes) {
            editor.getAllHighlights().none {
                // Some highlights carry no description: the remote call then returns null.
                val description: String? = it.getDescription()
                description?.contains("implicitly has type 'any'") == true
            }
        }
        editor.setFocus()
        return editor
    }

    private fun ScreenshotScope.waitForEditor(fileName: String): JEditorUiComponent {
        lateinit var editor: JEditorUiComponent
        waitFor("the editor of $fileName", 1.minutes) {
            editor = frame.codeEditor()
            editor.editor.getVirtualFile().getName() == fileName
        }
        return editor
    }

    private fun JEditorUiComponent.caretAtEndOf(fragment: String) {
        val start = text.indexOf(fragment)
        check(start >= 0) { "'$fragment' is not in the editor" }
        moveCaretToOffset(start + fragment.length)
    }


    /** Runs the IDE action [id] in [editor]: a simulated shortcut can land before the editor has the focus. */
    private fun ScreenshotScope.editorAction(editor: JEditorUiComponent, id: String) {
        driver.invokeAction(id, now = false, component = editor.component)
    }

    /** Lines [first]..[last] of [editor], gutter included, [width] pixels wide from the gutter's left edge. */
    private fun ScreenshotScope.lines(editor: JEditorUiComponent, first: Int, last: Int, width: Int): Rectangle {
        editor.scrollToPosition((first + last) / 2, 1)
        val gutter = frame.gutter()
        val top = editor.boundsOnScreen.y + editor.interact { visualLineToY(first - 1) }
        val bottom = editor.boundsOnScreen.y + editor.interact { visualLineToY(last) }
        return Rectangle(gutter.boundsOnScreen.x, top, width, bottom - top)
    }

    /** The list (a popup's) that renders [row]. */
    private fun ScreenshotScope.listShowing(row: String): UiComponent =
        driver.ui.xx { byType("javax.swing.JList") }.list().first { it.component.isShowing() && it.hasText(row) }

    /** Extract i18n key on the "Actions (from common namespace)" heading of Dashboard.tsx. */
    private fun ScreenshotScope.extractionDialog(): UiComponent {
        val editor = openEditor(DASHBOARD, reference(DASHBOARD))
        editor.caretAtEndOf("<h3>Actions (from")
        editorAction(editor, "ShowIntentionActions")
        waitForText("the intentions") { it == "Extract i18n key" }
        editor.keyboard {
            typeText("Extract i18n key")
            enter()
        }
        return driver.ui.dialog().waitFound(1.minutes)
    }
}
