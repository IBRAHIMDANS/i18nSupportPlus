package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Row
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent

/**
 * The translation dialog's message variables (`{{count}}`, `{name}`, `%s`): highlighted where they
 * appear in each value, and a warning row under each locale that drops one the others carry. See
 * [DialogViewModel]'s companion object for those rules — they are pure and tested there.
 */
internal class DialogVariables {

    private val warnings = LinkedHashMap<LocalizationSource, JBLabel>()
    private val warningRows = LinkedHashMap<LocalizationSource, Row>()

    /** Forgets the rows: the dialog is rebuilding them. */
    fun clear() {
        warnings.clear()
        warningRows.clear()
    }

    /** The warning label of [source], empty until its locale drops a variable. */
    fun warningOf(source: LocalizationSource): JBLabel =
        JBLabel().also {
            it.icon = AllIcons.General.Warning
            warnings[source] = it
        }

    /** The row holding [source]'s warning, hidden while it has nothing to say. */
    fun holdRow(source: LocalizationSource, row: Row) {
        warningRows[source] = row
    }

    /** The warning shown under [locale], empty when it drops nothing; for tests. */
    fun warningTextOf(locale: String): String? = warnings.entries.firstOrNull { it.key.localeLabel() == locale }?.value?.text

    /**
     * Highlights the message variables in every value of [areas] and flags the locales that drop one.
     * Runs on each keystroke: both are read-only passes over text already in memory.
     */
    fun refresh(areas: Map<LocalizationSource, JTextComponent>) {
        areas.values.forEach(::highlight)

        val valuesByLocale = areas.entries.associate { (source, area) -> source.localeLabel() to area.text }
        val missing = DialogViewModel.missingVariables(valuesByLocale)
        areas.keys.forEach { source ->
            val lost = missing[source.localeLabel()].orEmpty()
            warnings[source]?.text =
                if (lost.isEmpty()) ""
                else PluginBundle.message("dialog.translation.variables.missing", lost.sorted().joinToString(", "))
            warningRows[source]?.visible(lost.isNotEmpty())
        }
    }

    private fun highlight(textArea: JTextComponent) {
        textArea.highlighter?.let { highlighter ->
            highlighter.removeAllHighlights()
            val painter = DefaultHighlighter.DefaultHighlightPainter(VARIABLE_HIGHLIGHT)
            DialogViewModel.variableRanges(textArea.text).forEach { range ->
                // The document is read a hair after the ranges were computed; a range that no
                // longer fits is dropped rather than taking the dialog down with it.
                runCatching { highlighter.addHighlight(range.first, range.last + 1, painter) }
            }
        }
    }

    private companion object {
        /** Light enough to read black or white text through it, in either theme. */
        val VARIABLE_HIGHLIGHT = JBColor(0xFFF2C4, 0x4C4426)
    }
}
