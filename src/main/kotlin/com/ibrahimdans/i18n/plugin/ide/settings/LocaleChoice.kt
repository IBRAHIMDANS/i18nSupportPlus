package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.event.ActionListener
import javax.swing.ComboBoxEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * A locale field that also lists the project's locales: an editable combo whose editor is the
 * [JTextField] the page already binds to its setting.
 *
 * The text field stays the single source of truth — its name, its document listener and its
 * limits are untouched, so the settings UI tests still find and type into it. Picking a locale
 * from the list only writes the field. A locale the project does not have yet can still be typed.
 */
internal object LocaleChoice {

    /** An editable combo around [field], listing nothing until [offer] gives it the locales. */
    fun combo(field: JTextField): JComboBox<String> {
        val combo = JComboBox<String>()
        combo.isEditable = true
        // The combo draws the frame, as it does around its default editor.
        field.border = null
        combo.editor = FieldEditor(field)
        combo.model = DefaultComboBoxModel<String>().apply { selectedItem = field.text }
        return combo
    }

    /**
     * Lists [locales] in [combo], keeping what its field holds: a model replaced selects its first
     * item, which the editor would otherwise write into the setting.
     */
    fun offer(combo: JComboBox<String>, locales: List<String>) {
        val field = combo.editor.editorComponent as? JTextField ?: return
        val current = field.text
        combo.model = DefaultComboBoxModel(locales.distinct().toTypedArray()).apply { selectedItem = current }
    }

    /**
     * Finds the project's locales off the EDT, in smart mode, and hands them to [found] on the EDT
     * while the settings dialog is still open ([alive]). The page is usable before they arrive.
     */
    fun discover(project: Project, alive: () -> Boolean, found: (List<String>) -> Unit) {
        ReadAction.nonBlocking<List<String>> { TranslationDataLoader.discoverLocales(project) }
            .inSmartMode(project)
            .expireWhen { !alive() }
            .finishOnUiThread(ModalityState.any()) { locales -> if (alive()) found(locales) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * The combo's editor, made of the page's own field. A `null` item — what the combo sets while
     * it is being built, or after its model is replaced — leaves the field as it is.
     */
    private class FieldEditor(private val field: JTextField) : ComboBoxEditor {
        override fun getEditorComponent(): Component = field

        override fun setItem(anObject: Any?) {
            val text = anObject as? String ?: return
            if (field.text != text) field.text = text
        }

        override fun getItem(): Any = field.text

        override fun selectAll() = field.selectAll()

        override fun addActionListener(l: ActionListener) = field.addActionListener(l)

        override fun removeActionListener(l: ActionListener) = field.removeActionListener(l)
    }
}
