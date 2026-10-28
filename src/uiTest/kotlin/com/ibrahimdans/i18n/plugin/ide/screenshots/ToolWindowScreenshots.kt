package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.ibrahimdans.i18n.plugin.ide.toolwindow.I18nToolWindowPanel
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.components.elements.table
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.waitFor
import java.awt.Point
import javax.swing.JComboBox
import kotlin.time.Duration.Companion.minutes

/** The tool window images of the README, taken on examples/react-multi-namespace. */
object ToolWindowScreenshots {

    private val NAMESPACES = listOf("common", "auth", "dashboard", "deposit-box", "navigation")
    private const val TRANSLATIONS_ROOT = "Translations"
    private const val ALL_NAMESPACES = "All namespaces"

    val SESSION = ScreenshotSession(
        "toolwindow",
        listOf(
            Screenshot("toolwindow-tree-namespaces.png") {
                val panel = showView("Tree")
                val tree = panel.tree()
                // Collapsing a collapsed row is a no-op: no need to know which ones the view opened.
                for (namespace in NAMESPACES) tree.collapsePath(TRANSLATIONS_ROOT, namespace, fullMatch = false)
                tree.clickPath(TRANSLATIONS_ROOT, "dashboard", fullMatch = false)
                panel.boundsOnScreen
            },
            Screenshot("toolwindow-tree.png") {
                val panel = showView("Tree")
                val tree = panel.tree()
                for (namespace in listOf("common", "auth", "dashboard", "deposit-box")) {
                    tree.expandPath(TRANSLATIONS_ROOT, namespace, fullMatch = false)
                }
                tree.clickPath(TRANSLATIONS_ROOT, "dashboard", "notifications", fullMatch = false)
                panel.boundsOnScreen
            },
            Screenshot("toolwindow-table.png") {
                val panel = showTable()
                panel.waitOneText("Usage (not counted)", timeout = 1.minutes)
                panel.boundsOnScreen
            },
            Screenshot(
                "toolwindow-table-namespace-filter.png",
                cleanUp = { driver.ui.keyboard { escape() } },
            ) {
                val panel = showTable()
                panel.namespaceCombo().click()
                panel.boundsOnScreen
            },
            Screenshot(
                "toolwindow-table-orphans.png",
                cleanUp = {
                    driver.ui.keyboard { escape() }
                    toolWindow().namespaceCombo().selectItem(ALL_NAMESPACES)
                },
            ) {
                val panel = showTable()
                panel.namespaceCombo().selectItem("deposit-box")
                panel.x { byAccessibleName("Count Key Usages") }.waitFound(1.minutes).click()
                val table = panel.table()
                // The Usage column is narrow: the cell reads "No referen…".
                table.waitOneText(timeout = 2.minutes) { it.text.startsWith("No referen") }
                // The table opens its columns at fixed widths: widened as on the original image,
                // so the keys and the "No reference" label read in full.
                driver.resizeColumns(table.component, mapOf(0 to 515, 3 to 210))
                val (row, _) = table.findRowColumn { it.endsWith("activity.entry.fileAdded") }
                table.rightClickCell(row, 0)
                val deleteItem = driver.ui.x { byVisibleText("Delete Key with No Reference") }.waitFound(1.minutes)
                // Hovered, as a user about to pick it: the menu shows its selection highlight.
                deleteItem.moveMouse()
                waitFor("the menu item to be highlighted", 1.minutes) { deleteItem.component.isShowing() }
                panel.boundsOnScreen
            }.withPointer(),
            Screenshot("toolwindow-stats.png") {
                showView("Stats").boundsOnScreen
            },
            Screenshot(
                "toolwindow-stats-popup.png",
                cleanUp = { driver.ui.keyboard { escape() } },
            ) {
                val panel = showView("Stats")
                val table = panel.table()
                val (row, _) = table.findRowColumn { it == "common (default)" }
                val (_, frColumn) = table.findRowColumn { it.startsWith("11/13") }
                table.clickCell(row, frColumn)
                waitForText("the missing keys popup") { it.startsWith("actions.eddit") }
                panel.boundsOnScreen
            },
            Screenshot("toolwindow-create-translation.png") {
                val dialog = openCreateTranslation()
                dialog.boundsOnScreen
            },
            Screenshot(
                "toolwindow-create-namespace.png",
                cleanUp = {
                    driver.ui.keyboard { escape() }
                    createTranslationDialog().pressButton("Cancel")
                },
            ) {
                // Taken over the dialog the previous screenshot left open, or a new one.
                val dialog = if (driver.ui.isDialogOpened()) createTranslationDialog() else openCreateTranslation()
                dialog.x { byVisibleText("+") }.click()
                val prompt = driver.ui.x { byVisibleText("Namespace name (letters, digits, hyphens):") }.waitFound(1.minutes)
                prompt.keyboard { typeText("settings") }
                dialog.boundsOnScreen
            },
            Screenshot(
                "sync-missing-keys.png",
                cleanUp = { if (driver.ui.isDialogOpened()) driver.ui.dialog().pressButton("Cancel") },
            ) {
                val panel = showTable()
                panel.x { byAccessibleName("Sync Keys…") }.waitFound(1.minutes).click()
                val preview = driver.ui.dialog().also { it.waitFound(1.minutes) }
                preview.waitOneText("actions.eddit", timeout = 1.minutes)
                preview.boundsOnScreen
            },
        ),
    )

    private fun Screenshot.withPointer() = Screenshot(fileName, keepPointer = true, cleanUp = cleanUp, take = take)

    private fun ScreenshotScope.toolWindow(): UiComponent {
        driver.openToolWindow("I18n")
        return frame.x { byType(I18nToolWindowPanel::class.java) }.waitFound(1.minutes)
    }

    private fun ScreenshotScope.showView(name: String): UiComponent {
        val panel = toolWindow()
        panel.x { byVisibleText(name) }.waitFound(1.minutes).click()
        return panel
    }

    private fun ScreenshotScope.showTable(): UiComponent {
        val panel = showView("Table")
        panel.table().waitOneText("actions.eddit", timeout = 1.minutes)
        return panel
    }

    private fun UiComponent.namespaceCombo(): JComboBoxUiComponent =
        xx(JComboBoxUiComponent::class.java) { byType(JComboBox::class.java) }.list()
            .first { it.getSelectedItem() in setOf(ALL_NAMESPACES) + NAMESPACES }

    private fun ScreenshotScope.createTranslationDialog(): DialogUiComponent =
        driver.ui.dialog(title = "Create Translation").also { it.waitFound(1.minutes) }

    /** Opens Create Translation from the toolbar and fills it as a user adding a sign-up label. */
    private fun ScreenshotScope.openCreateTranslation(): DialogUiComponent {
        val panel = showView("Table")
        panel.x { byAccessibleName("Add Translation") }.waitFound(1.minutes).click()
        val dialog = createTranslationDialog()
        dialog.xx(JComboBoxUiComponent::class.java) { byType(JComboBox::class.java) }.list().first().selectItem("auth")
        val keyField = dialog.x { byType("com.intellij.ui.components.JBTextField") }
        keyField.click()
        keyField.keyboard { typeText("login.button.signUp") }
        dialog.waitOneText("Key is available", timeout = 1.minutes)
        // One text area per locale, in the order the index returns the files (not sorted): the
        // English one is the last area above its path caption.
        val dialogTop = dialog.boundsOnScreen.y
        val captionY = dialog.getAllTexts().first { it.text == "public/locales/en/auth.json" }.point.y
        val english = dialog.xx { byType("com.intellij.ui.components.JBTextArea") }.list()
            .filter { it.boundsOnScreen.y - dialogTop < captionY }
            .maxBy { it.boundsOnScreen.y }
        english.click(Point(10, 10))
        english.keyboard { typeText("Create an account") }
        return dialog
    }
}
