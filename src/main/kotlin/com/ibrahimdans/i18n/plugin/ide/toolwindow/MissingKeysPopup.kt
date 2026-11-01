package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/** Bounds of the popup listing untranslated keys, unscaled pixels; rows beyond the max scroll. */
private const val POPUP_MAX_ROWS = 12
private const val POPUP_MIN_WIDTH = 320
private const val POPUP_MAX_WIDTH = 720
private const val POPUP_PADDING = 24
/** Room the popup's title bar keeps around its text: insets and the resize/move gestures. */
private const val POPUP_TITLE_PADDING = 48
/** Title bar plus hint line of the popup, added to the list's own height. */
private const val POPUP_CHROME_HEIGHT = 56

/**
 * Width of the untranslated-keys popup: wide enough for its widest line and for its title,
 * within [min] and [max]. Sized on the lines alone, a short list cut a long title such as
 * `common (default) — 'fr': 2 missing, 0 empty — reference: en` in the middle of a word.
 */
internal fun missingKeysPopupWidth(contentWidth: Int, titleWidth: Int, padding: Int, titlePadding: Int, min: Int, max: Int): Int =
    maxOf(contentWidth + padding, titleWidth + titlePadding).coerceIn(min, max)

/**
 * The popup the Stats tab opens under a locale cell: the untranslated keys of that cell — one
 * namespace in one locale, or the whole locale from the total row — the missing ones first, then
 * the empty ones, each wearing the tree's mark for its state and what the key says in the reference
 * locale, which is what the translator needs to read.
 *
 * Enter or a click calls [onTranslate] with the key, the one way to *fix* what the popup lists; F4
 * calls [onReference] instead, to open the reference file at the key. The popup used to open far
 * from the cell, at a fixed 480×320 for two lines, and its only gesture led to the reference file —
 * reading the gap rather than closing it.
 */
internal class MissingKeysPopup(
    /** Every key of the report with its value per locale: where the reference value is read. */
    private val translations: Map<String, Map<String, String>>,
    private val onTranslate: (String) -> Unit,
    private val onReference: (String, String) -> Unit,
) {

    /** One line of the popup: a key the locale lacks ([LocaleState.MISSING]) or leaves blank ([LocaleState.EMPTY]). */
    data class UntranslatedKey(val key: String, val state: LocaleState)

    /** The popup's lines for [cell]: its missing keys, then its empty ones. */
    fun lines(cell: LocaleStats): List<UntranslatedKey> =
        cell.missingKeys.map { UntranslatedKey(it, LocaleState.MISSING) } + cell.emptyKeys.map { UntranslatedKey(it, LocaleState.EMPTY) }

    /** Shows the popup of [cell], titled after [rowLabel], at [at]; values read in [referenceLocale]. */
    fun show(rowLabel: String, cell: LocaleStats, referenceLocale: String, at: RelativePoint) {
        val listModel = DefaultListModel<UntranslatedKey>()
        lines(cell).forEach(listModel::addElement)
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = listModel.size.coerceIn(1, POPUP_MAX_ROWS)
        list.cellRenderer = object : ColoredListCellRenderer<UntranslatedKey>() {
            override fun customizeCellRenderer(list: JList<out UntranslatedKey>, value: UntranslatedKey, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (value.state == LocaleState.EMPTY) ICON_EMPTY else ICON_MISSING
                append(value.key)
                val reference = translations[value.key]?.get(referenceLocale)
                if (!reference.isNullOrBlank()) {
                    append("  ")
                    append(displayValue(reference), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                append("  ")
                append(
                    PluginBundle.message(if (value.state == LocaleState.EMPTY) "toolwindow.tree.status.empty" else "toolwindow.tree.status.missing"),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                )
            }
        }
        ListSpeedSearch.installOn(list) { it.key }

        val title = PluginBundle.message(
            "toolwindow.stats.missing.popup.title",
            rowLabel, cell.locale, cell.missing, cell.empty, referenceLocale
        )
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JScrollPane(list), list)
            .setTitle(title)
            .setAdText(PluginBundle.message("toolwindow.stats.missing.popup.hint", referenceLocale))
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()

        val translate = {
            list.selectedValue?.let { chosen ->
                popup.cancel()
                onTranslate(chosen.key)
            }
        }
        val reference = {
            list.selectedValue?.let { chosen ->
                popup.cancel()
                onReference(chosen.key, referenceLocale)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && list.locationToIndex(e.point) >= 0) translate()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> translate()
                    KeyEvent.VK_F4 -> reference()
                }
            }
        })
        list.selectedIndex = 0

        // Under the cell, sized to its lines: the popup is an annotation of the cell, not a window.
        // The title is measured in the bold label font the popup header uses, so it is not cut.
        val preferred = list.preferredScrollableViewportSize
        val titleWidth = list.getFontMetrics(JBUI.Fonts.label().asBold()).stringWidth(title)
        popup.size = Dimension(
            missingKeysPopupWidth(
                preferred.width, titleWidth,
                JBUI.scale(POPUP_PADDING), JBUI.scale(POPUP_TITLE_PADDING),
                JBUI.scale(POPUP_MIN_WIDTH), JBUI.scale(POPUP_MAX_WIDTH)
            ),
            preferred.height + JBUI.scale(POPUP_CHROME_HEIGHT)
        )
        popup.show(at)
    }

    companion object {
        /** One line of [value], so a multi-line reference does not stretch the popup. */
        fun displayValue(value: String): String =
            value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
