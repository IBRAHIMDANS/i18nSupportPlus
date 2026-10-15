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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * The review of one key in one locale: its value now, what the review means for it, the thread,
 * a field to write in, and the decisions — *Approve*, *Request Changes*, *Mark for Review* — next to
 * *Comment*.
 *
 * A decision closes the dialog; a comment stays, the thread reloaded under it. *Request Changes*
 * takes the text of the field and refuses an empty one. Every write is one command (see
 * [ReviewActions]). Nothing can be decided on a locale without a value: the buttons are disabled.
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
            .addLabeledComponentFillVertically(PluginBundle.message("review.dialog.new.comment"), JBScrollPane(input))
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
        return written(ReviewActions.requestChanges(project, root, listOf(cell), text, defaultNamespace, referenceLocale))
    }

    /** Adds the field's text to the thread and keeps the dialog open on it. */
    internal fun postComment(): Boolean {
        val text = input.text
        if (text.isBlank()) {
            setErrorText(PluginBundle.message("review.comment.empty"), input)
            return false
        }
        val ok = written(ReviewActions.comment(project, root, cell, text, defaultNamespace, referenceLocale))
        if (ok) {
            input.text = ""
            setErrorText(null)
            reload()
        }
        return ok
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
        threadPane.text = threadHtml(review?.thread.orEmpty())
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

        /** The thread, oldest first, every text escaped. */
        internal fun threadHtml(thread: List<ReviewEvent>): String {
            if (thread.isEmpty()) return "<html><i>${escape(PluginBundle.message("review.dialog.thread.empty"))}</i></html>"
            return "<html>" + thread.joinToString("") { event ->
                val text = if (event.text.isBlank()) "" else "<br>" + escape(event.text)
                "<p><b>${escape(eventLine(event))}</b>$text</p>"
            } + "</html>"
        }

        private fun escape(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
    }
}
