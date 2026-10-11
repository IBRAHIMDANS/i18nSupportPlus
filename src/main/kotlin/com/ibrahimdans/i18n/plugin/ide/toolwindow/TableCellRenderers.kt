package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewEvent
import com.ibrahimdans.i18n.plugin.review.ReviewEventKind
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.swing.Icon
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/** The review a locale cell shows: what it means for the value held now, and the stored review behind it. */
internal data class CellReview(val shown: ShownReview, val review: Review?) {
    companion object {
        val NONE = CellReview(ShownReview.NONE, null)
    }
}

/**
 * How a review reads, in a table cell and in the review dialog: an icon and words, never a tint
 * alone — the rule [ValueStatus] follows. Platform icons only: the eye of *to review* (whatever
 * made it so), the check of *approved*, the red balloon of *changes requested*, which carries a
 * comment by definition.
 */
internal object ReviewBadge {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun icon(shown: ShownReview): Icon? = when (shown) {
        ShownReview.NONE -> null
        ShownReview.TO_REVIEW, ShownReview.CHANGED_SINCE_APPROVAL, ShownReview.FIXED_SINCE_REQUEST -> AllIcons.Actions.Show
        ShownReview.APPROVED -> AllIcons.General.InspectionsOK
        ShownReview.CHANGES_REQUESTED -> AllIcons.General.BalloonError
    }

    /** The state in words; empty for [ShownReview.NONE], which shows nothing. */
    fun label(shown: ShownReview): String = when (shown) {
        ShownReview.NONE -> ""
        ShownReview.TO_REVIEW -> PluginBundle.message("review.state.to.review")
        ShownReview.CHANGED_SINCE_APPROVAL -> PluginBundle.message("review.state.changed.since.approval")
        ShownReview.FIXED_SINCE_REQUEST -> PluginBundle.message("review.state.fixed.since.request")
        ShownReview.APPROVED -> PluginBundle.message("review.state.approved")
        ShownReview.CHANGES_REQUESTED -> PluginBundle.message("review.state.changes.requested")
    }

    /** Where the value came from: `manual`, `i18nize`, `machine:<engine>`; an origin of a newer version reads as is. */
    fun origin(origin: String): String = when {
        origin == Review.ORIGIN_MANUAL -> PluginBundle.message("review.origin.manual")
        origin == Review.ORIGIN_I18NIZE -> PluginBundle.message("review.origin.i18nize")
        origin.startsWith(MACHINE_PREFIX) -> PluginBundle.message("review.origin.machine", origin.removePrefix(MACHINE_PREFIX))
        else -> origin
    }

    /** An ISO-8601 instant as a local date and time; a value that is not one reads as is. */
    fun date(at: String): String = try {
        DATE.withZone(ZoneId.systemDefault()).format(Instant.parse(at))
    } catch (_: DateTimeParseException) {
        at
    }

    /** The last entry of the thread that says something: decisions without text are left out. */
    fun lastComment(review: Review): ReviewEvent? = review.thread.lastOrNull { it.text.isNotBlank() }

    /** What a thread entry did, in words: `approved`, `requested changes`… */
    fun eventKind(kind: ReviewEventKind): String = when (kind) {
        ReviewEventKind.COMMENT -> PluginBundle.message("review.event.comment")
        ReviewEventKind.TO_REVIEW -> PluginBundle.message("review.event.to.review")
        ReviewEventKind.APPROVE -> PluginBundle.message("review.event.approve")
        ReviewEventKind.REQUEST_CHANGE -> PluginBundle.message("review.event.request.change")
    }

    /** The lines a tooltip adds for [review]: state, last decision, origin, last comment. Plain text. */
    fun lines(review: Review, shown: ShownReview): List<String> = listOfNotNull(
        label(shown),
        PluginBundle.message("review.tooltip.decision", review.by, date(review.at)),
        PluginBundle.message("review.tooltip.origin", origin(review.origin)),
        lastComment(review)?.let { PluginBundle.message("review.tooltip.comment", it.by, it.text) },
    )

    /**
     * The tooltip of a reviewed cell: [base] — the value, or what the cell says about it — then the
     * review's [lines]. HTML, every text escaped: a value or a comment holding `<b>` is shown as typed.
     */
    fun tooltip(base: String?, review: Review, shown: ShownReview): String {
        val parts = listOfNotNull(base?.takeIf { it.isNotEmpty() }?.let(::html)) +
            lines(review, shown).mapIndexed { i, line -> if (i == 0) "<b>${html(line)}</b>" else html(line) }
        return "<html>" + parts.joinToString("<br>") + "</html>"
    }

    private fun html(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

    private const val MACHINE_PREFIX = "machine:"
}

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
 *
 * Where reviews are on, [review] says what the cell's review is — asked with the view row and
 * column and the cell's value — and the cell carries its [ReviewBadge] icon, its tooltip the
 * state, the last decision, the origin and the last comment. The icon of a missing or blank value
 * stays: that is the first thing to fix.
 */
internal class TranslationCellRenderer(
    private val leading: Int,
    private val localeCount: Int,
    private val viewModel: TableViewModel,
    private val review: (row: Int, column: Int, raw: String) -> CellReview = { _, _, _ -> CellReview.NONE },
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
        val cellReview = review(row, column, raw)
        if (cellReview.shown != ShownReview.NONE && cellReview.review != null) {
            if (icon == null) icon = ReviewBadge.icon(cellReview.shown)
            toolTipText = ReviewBadge.tooltip(toolTipText, cellReview.review, cellReview.shown)
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
