package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Font
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

// Not `const`: these come from the bundle now.
private val NOT_SCANNED_TOOLTIP = PluginBundle.message("toolwindow.table.usage.not.scanned")
private val NOT_SCANNED_LABEL = PluginBundle.message("toolwindow.table.usage.pending")
private val ORPHAN_LABEL = PluginBundle.message("toolwindow.table.usage.orphan")
private val ORPHAN_TOOLTIP = PluginBundle.message("toolwindow.table.usage.orphan.tooltip")
private val DYNAMIC_LABEL = PluginBundle.message("toolwindow.table.usage.dynamic")
private val DYNAMIC_TOOLTIP = PluginBundle.message("toolwindow.table.usage.dynamic.tooltip")
private val KEPT_LABEL = PluginBundle.message("toolwindow.table.usage.kept")
private val KEPT_TOOLTIP = PluginBundle.message("toolwindow.table.usage.kept.tooltip")
private val MISSING_LABEL = PluginBundle.message("toolwindow.table.value.missing")
private val MISSING_TOOLTIP = PluginBundle.message("toolwindow.table.value.missing.tooltip")
private val BLANK_LABEL = PluginBundle.message("toolwindow.table.value.blank")
private val BLANK_TOOLTIP = PluginBundle.message("toolwindow.table.value.blank.tooltip")

// The IDE's own file-colour tints rather than six invented RGB values: they are the palette
// themes already redefine, so the table follows a dark or high-contrast theme instead of
// fighting it. They only ever *reinforce* a state the cell also spells out in words.
private val MISSING_BACKGROUND = JBColor.namedColor("FileColor.Rose", JBColor.PINK)
private val BLANK_BACKGROUND = JBColor.namedColor("FileColor.Yellow", JBColor.YELLOW)
private val ORPHAN_FOREGROUND = JBColor.namedColor("Label.errorForeground", JBColor.RED)
private val NOT_SCANNED_FOREGROUND = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

// Not the orphan red: the key is reachable, only not by a name written anywhere.
private val DYNAMIC_FOREGROUND = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

/**
 * Cell renderer for the key and locale columns.
 *
 * A locale cell says what it is — an icon and a word for a missing or an empty value,
 * the value itself otherwise — and the background tint only repeats it. Before, the tint
 * was the whole message: a cell with no entry and a cell holding `"   "` differed by two
 * shades of nothing, and neither was distinguishable from a translated cell in greyscale.
 *
 * The leading columns are the key's: with a Namespace column in front, the namespace cell
 * is set in bold — the tree does the same on its group rows — and the key cell drops the
 * prefix the namespace cell already shows, keeping the full key in its tooltip.
 *
 * [leading] is the number of columns before the locales (Key alone, or Namespace + Key);
 * [localeCount] the number of locale columns that follow.
 */
internal class TranslationCellRenderer(
    private val leading: Int,
    private val localeCount: Int,
    private val viewModel: TableViewModel,
) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val raw = value?.toString() ?: ""
        // DefaultTableCellRenderer reuses one component for every cell: whatever the
        // previous cell set has to be cleared, not merely overwritten on some branches.
        icon = null
        toolTipText = null
        font = table.font
        if (!isSelected) background = table.background

        if (column < leading) {
            if (leading > 1 && column == 0) font = table.font.deriveFont(Font.BOLD)
            if (leading > 1 && column == leading - 1) {
                text = viewModel.keyLabel(raw)
                toolTipText = raw
            }
            return component
        }
        if (column >= leading + localeCount) return component

        when (viewModel.valueStatus(raw)) {
            ValueStatus.MISSING -> {
                text = MISSING_LABEL
                icon = AllIcons.General.Error
                toolTipText = MISSING_TOOLTIP
                if (!isSelected) background = MISSING_BACKGROUND
            }

            ValueStatus.BLANK -> {
                text = BLANK_LABEL
                icon = AllIcons.General.Warning
                toolTipText = BLANK_TOOLTIP
                if (!isSelected) background = BLANK_BACKGROUND
            }

            // Values arrive verbatim from the translation files (newlines, indentation):
            // render a collapsed single line, keep the full raw value in the tooltip.
            ValueStatus.TRANSLATED -> {
                text = displayValue(raw)
                toolTipText = raw
            }
        }
        return component
    }
}

/**
 * Cell renderer for the "Usage" column, whose model value is the raw count.
 *
 * Never scanned, unused and used are three states, and the column used to separate them
 * by foreground colour alone — with `—` standing in for the first. Each now carries its
 * own icon and wording; the colour follows.
 */
internal class UsageCellRenderer(private val viewModel: TableViewModel) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val count = (value as? Int) ?: -1
        icon = null
        toolTipText = null
        if (!isSelected) {
            background = table.background
            foreground = table.foreground
        }

        when (viewModel.usageStatus(count)) {
            UsageStatus.NOT_SCANNED -> {
                text = NOT_SCANNED_LABEL
                icon = AllIcons.General.Information
                toolTipText = NOT_SCANNED_TOOLTIP
                if (!isSelected) foreground = NOT_SCANNED_FOREGROUND
            }

            UsageStatus.ORPHAN -> {
                text = ORPHAN_LABEL
                icon = AllIcons.General.Warning
                toolTipText = ORPHAN_TOOLTIP
                if (!isSelected) foreground = ORPHAN_FOREGROUND
            }

            UsageStatus.DYNAMIC -> {
                text = DYNAMIC_LABEL
                icon = AllIcons.General.Information
                toolTipText = DYNAMIC_TOOLTIP
                if (!isSelected) foreground = DYNAMIC_FOREGROUND
            }

            UsageStatus.KEPT -> {
                text = KEPT_LABEL
                icon = AllIcons.General.Information
                toolTipText = KEPT_TOOLTIP
                if (!isSelected) foreground = DYNAMIC_FOREGROUND
            }

            UsageStatus.USED -> text = count.toString()
        }
        return component
    }
}
