package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewEvent
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * The review of one key in one locale: its value now, what the review means for it, the thread,
 * a field to write in, and the decisions — *Approve*, *Request Changes*, *Mark for Review* — next to
 * *Comment*.
 *
 * A decision closes the dialog; a comment stays, the thread reloaded under it. *Request Changes*
 * takes the text of the field and refuses an empty one, and the suggested value when one is typed:
 * the change request then offers *Apply suggestion* in the thread, which writes it as the value and
 * marks the key to review — then closes the dialog, as a decision does, its value being stale.
 * Each entry of the thread offers *Reply* — the next comment then answers it, shown indented under
 * the entry its discussion starts from — and an entry with a text *Resolve*: it is then shown as one
 * greyed line, its replies hidden, until *Reopen*. Resolving decides nothing: the state stays.
 * Every write is one command (see [ReviewActions]). Nothing can be decided on a locale without a value: the buttons are disabled.
 *
 * Opened from the table's *Comments…* and, through [open], from wherever a key and a locale are
 * known — the key dialog's *Review…* link.
 *
 * [referenceLocale] is the module's reference locale (`ReferenceLocale.of`), the one
 * [ReviewCell.source] is read in: the state is shown against that source — *source changed* when
 * the value was decided on another — and every decision records it. Null when unknown: the state
 * ignores the source, and a decision keeps the one the key had.
 */
class ReviewThreadDialog(
    private val project: Project,
    private val root: VirtualFile,
    private val cell: ReviewCell,
    private val defaultNamespace: String = ReviewActions.defaultNamespace(project),
    private val referenceLocale: String? = null,
) : DialogWrapper(project) {

    private val reviewKey = ReviewKey.fromSpelling(cell.key, defaultNamespace)

    private val stateLabel = JBLabel()
    private val threadPane = JEditorPane("text/html", "").apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }
    internal val input = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    /** The value a change request proposes instead; left empty, it proposes none. */
    internal val suggestionField = JBTextField()

    /** The entry the next comment replies to, null when it replies to none. */
    private var replyTo: ReviewEvent? = null
    private val replyLabel = JBLabel()
    private val replyPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isVisible = false
        add(replyLabel)
        add(ActionLink(PluginBundle.message("review.dialog.reply.cancel")) { reply(null) })
    }

    private val hasValue = cell.value.isNotEmpty()

    private val approveAction = decision("review.action.approve") { approve() }
    private val requestAction = decision("review.action.request.changes") { requestChanges() }
    private val markAction = decision("review.action.mark") {
        written(ReviewActions.markToReview(project, root, listOf(cell), defaultNamespace, referenceLocale))
    }
    private val commentAction = object : AbstractAction(PluginBundle.message("review.dialog.comment")) {
        override fun actionPerformed(e: ActionEvent) {
            postComment()
        }
    }

    init {
        title = PluginBundle.message("review.dialog.title", cell.key, cell.locale)
        setCancelButtonText(PluginBundle.message("review.dialog.close"))
        listOf(approveAction, requestAction, markAction, commentAction).forEach { it.isEnabled = hasValue }
        threadPane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) follow(e.description)
        }
        init()
        reload()
    }

    /** The stored review, read again from the store. */
    internal fun review(): Review? = ReviewStore.getInstance(project).reviews(root, cell.locale)[reviewKey]

    /** What the review means for the value the cell holds, against the source the key holds now. */
    internal fun shownReview(): ShownReview =
        shown(review(), cell.value.takeIf { it.isNotEmpty() }, ReviewActions.sourceOf(cell, referenceLocale))

    /** The thread as the dialog shows it. */
    internal fun threadText(): String = threadPane.text

    /** The state line as the dialog shows it. */
    internal fun stateText(): String = stateLabel.text

    override fun createCenterPanel(): JComponent {
        val value = JBTextArea(if (hasValue) cell.value else PluginBundle.message("review.dialog.value.missing")).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(2)
        }
        val header = FormBuilder.createFormBuilder()
            .addLabeledComponent(PluginBundle.message("review.dialog.value"), value)
            .addLabeledComponent(PluginBundle.message("review.dialog.state"), stateLabel)
            .panel
        val thread = JBScrollPane(threadPane).apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(220)) }
        val footer = FormBuilder.createFormBuilder()
            .addComponent(replyPanel)
            .addLabeledComponentFillVertically(PluginBundle.message("review.dialog.new.comment"), JBScrollPane(input))
            .addLabeledComponent(PluginBundle.message("review.dialog.suggestion"), suggestionField)
            .panel
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(header, BorderLayout.NORTH)
            add(FormBuilder.createFormBuilder().addLabeledComponentFillVertically(PluginBundle.message("review.dialog.thread"), thread).panel, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = input

    override fun createActions(): Array<Action> = arrayOf(approveAction, requestAction, markAction, commentAction, cancelAction)

    /** Approves the cell's value on its current source. Internal for the tests. */
    internal fun approve(): Boolean = written(ReviewActions.approve(project, root, listOf(cell), defaultNamespace, referenceLocale))

    /**
     * Requests changes with the field's text. Refused — the field flagged, nothing written — when
     * it is empty. Internal for the tests, as are [postComment] and the other entry points.
     */
    internal fun requestChanges(): Boolean {
        val text = input.text
        if (text.isBlank()) {
            setErrorText(PluginBundle.message("review.request.changes.empty"), input)
            return false
        }
        return written(
            ReviewActions.requestChanges(project, root, listOf(cell), text, defaultNamespace, referenceLocale, suggestionField.text)
        )
    }

    /** Writes the value the thread entry [id] suggests, and marks the key to review on it. */
    internal fun applySuggestion(id: String): Boolean {
        val suggestion = review()?.thread?.firstOrNull { it.id == id }?.suggestion ?: return false
        val ok = ReviewActions.applySuggestion(project, root, cell, suggestion, defaultNamespace, referenceLocale)
        if (!ok) setErrorText(PluginBundle.message("review.apply.suggestion.failed", cell.key, cell.locale))
        return ok
    }

    /**
     * Adds the field's text to the thread — in reply to the entry [reply] chose, if any — and keeps
     * the dialog open on it.
     */
    internal fun postComment(): Boolean {
        val text = input.text
        if (text.isBlank()) {
            setErrorText(PluginBundle.message("review.comment.empty"), input)
            return false
        }
        val ok = written(ReviewActions.comment(project, root, cell, text, defaultNamespace, referenceLocale, replyTo?.id))
        if (ok) {
            input.text = ""
            setErrorText(null)
            reply(null)
            reload()
        }
        return ok
    }

    /** Makes the next comment a reply to the entry [id] — none when null or unknown — and says so above the field. */
    internal fun reply(id: String?) {
        replyTo = id?.let { wanted -> review()?.thread?.firstOrNull { it.id == wanted } }
        replyLabel.text = replyTo?.let { PluginBundle.message("review.dialog.replying", it.by) }.orEmpty()
        replyPanel.isVisible = replyTo != null
        if (replyTo != null) input.requestFocusInWindow()
    }

    /** The id of the entry the next comment replies to. */
    internal fun replyingTo(): String? = replyTo?.id

    /** Marks the entry [id] [resolved], or open again, and shows the thread so. */
    internal fun resolve(id: String, resolved: Boolean): Boolean {
        val ok = written(ReviewActions.resolve(project, root, cell, id, resolved, defaultNamespace))
        if (ok) reload()
        return ok
    }

    /** Runs the link of the thread [link] names: `<action>:<entry id>`. */
    private fun follow(link: String) {
        val id = link.substringAfter(':')
        when (link.substringBefore(':') + ":") {
            APPLY_LINK -> if (applySuggestion(id)) close(OK_EXIT_CODE)
            REPLY_LINK -> reply(id)
            RESOLVE_LINK -> resolve(id, true)
            REOPEN_LINK -> resolve(id, false)
        }
    }

    /** [ok] as is; when false, the field says the review file could not be written. */
    private fun written(ok: Boolean): Boolean {
        if (!ok) setErrorText(PluginBundle.message("review.write.failed", cell.locale))
        return ok
    }

    private fun reload() {
        val review = review()
        val shown = shownReview()
        stateLabel.text = if (shown == ShownReview.NONE) PluginBundle.message("review.dialog.state.none") else ReviewBadge.label(shown)
        stateLabel.icon = ReviewBadge.icon(shown)
        threadPane.text = threadHtml(review?.thread.orEmpty(), cell.value)
        threadPane.caretPosition = threadPane.document.length
    }

    /** A decision button: closes the dialog once written, stays open — the reason shown — otherwise. */
    private fun decision(labelKey: String, run: () -> Boolean) = object : AbstractAction(PluginBundle.message(labelKey)) {
        override fun actionPerformed(e: ActionEvent) {
            if (run()) close(OK_EXIT_CODE)
        }
    }

    companion object {

        /**
         * Opens the review of [key] — spelled as the tool window spells it — in [locale], for
         * [moduleConfig] (null: the project's reviews). [value] is the value the locale holds now,
         * empty when it has none; [source] the text the key holds now in [referenceLocale], the
         * module's reference locale (`ReferenceLocale.of`) — both null when unknown. Returns false,
         * showing nothing, when reviews are off for the module; true once the dialog is closed.
         */
        fun open(
            project: Project, moduleConfig: ModuleConfig?, key: String, locale: String, value: String,
            source: String? = null, referenceLocale: String? = null,
        ): Boolean {
            val root = ReviewActions.enabledRoot(project, moduleConfig) ?: return false
            ReviewThreadDialog(project, root, ReviewCell(key, locale, value, source), referenceLocale = referenceLocale).show()
            return true
        }

        /** One entry of the thread: who did what, when, and what they said. */
        internal fun eventLine(event: ReviewEvent): String =
            PluginBundle.message("review.dialog.event", event.by, ReviewBadge.eventKind(event.kind), ReviewBadge.date(event.at))

        /** The links of the thread, each followed by the id of the entry it acts on. */
        internal const val APPLY_LINK = "apply:"
        internal const val REPLY_LINK = "reply:"
        internal const val RESOLVE_LINK = "resolve:"
        internal const val REOPEN_LINK = "reopen:"

        /**
         * The thread, oldest first, every text escaped. A reply is indented under the entry its
         * discussion starts from — one level, however deep it answers; one whose parent is unknown
         * stands on its own. A resolved entry is one greyed line, its replies hidden.
         *
         * A suggested value is shown under its entry. While the cell holds a [value], the links act
         * on the thread: *Reply* on every entry, *Resolve* or *Reopen* on one with a text, and
         * *Apply suggestion* when the value is not the suggested one already.
         */
        internal fun threadHtml(thread: List<ReviewEvent>, value: String = ""): String {
            if (thread.isEmpty()) return "<html><i>${escape(PluginBundle.message("review.dialog.thread.empty"))}</i></html>"
            val byId = thread.associateBy { it.id }
            val discussions = thread.groupBy { startOf(it, byId).id }
            return "<html>" + thread.filter { startOf(it, byId) == it }.joinToString("") { start ->
                if (start.resolved) return@joinToString resolvedHtml(start, value)
                val replies = discussions[start.id].orEmpty().filter { it != start }
                entryHtml(start, value, canResolve = true) +
                    replies.joinToString("") { "<div style=\"margin-left: 20px\">${entryHtml(it, value, canResolve = false)}</div>" }
            } + "</html>"
        }

        /** The entry [event]'s discussion starts from: its farthest known parent, itself when it replies to none. */
        private fun startOf(event: ReviewEvent, byId: Map<String, ReviewEvent>): ReviewEvent {
            var current = event
            val seen = mutableSetOf(event.id)
            while (true) {
                val parent = current.parent?.let { byId[it] } ?: return current
                // A hand edit may loop the parents: every entry of the loop then stands on its own.
                if (!seen.add(parent.id)) return event
                current = parent
            }
        }

        private fun entryHtml(event: ReviewEvent, value: String, canResolve: Boolean): String {
            val text = if (event.text.isBlank()) "" else "<br>" + escape(event.text)
            val links = if (value.isEmpty()) "" else buildString {
                append("<br>").append(link(REPLY_LINK, event, "review.dialog.reply"))
                if (canResolve && event.text.isNotBlank()) append(" ").append(link(RESOLVE_LINK, event, "review.dialog.resolve"))
            }
            return "<p><b>${escape(eventLine(event))}</b>$text${suggestionHtml(event, value)}$links</p>"
        }

        private fun resolvedHtml(event: ReviewEvent, value: String): String {
            val reopen = if (value.isEmpty()) "" else " " + link(REOPEN_LINK, event, "review.dialog.reopen")
            return "<p><font color=\"#808080\">${escape(PluginBundle.message("review.dialog.resolved", eventLine(event)))}</font>$reopen</p>"
        }

        private fun link(action: String, event: ReviewEvent, labelKey: String): String =
            "<a href=\"$action${escape(event.id)}\">${escape(PluginBundle.message(labelKey))}</a>"

        private fun suggestionHtml(event: ReviewEvent, value: String): String {
            val suggestion = event.suggestion ?: return ""
            val line = "<br><i>${escape(PluginBundle.message("review.dialog.suggested", suggestion))}</i>"
            if (value.isEmpty() || suggestion == value) return line
            return "$line " + link(APPLY_LINK, event, "review.dialog.apply.suggestion")
        }

        private fun escape(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
    }
}
