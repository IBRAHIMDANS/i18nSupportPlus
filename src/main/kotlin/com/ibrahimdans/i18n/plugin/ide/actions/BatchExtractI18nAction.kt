package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

private val JS_EXTENSIONS = setOf("js", "jsx", "ts", "tsx")

class BatchExtractI18nAction : AnAction() {

    private val keyCreator = KeyCreator()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.extension?.lowercase() in JS_EXTENSIONS
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val candidates = collectCandidates(psiFile)
        if (candidates.isEmpty()) return

        // [ExistingKeyFinder] walks the file-type index, which the platform forbids on the EDT.
        ReadAction.nonBlocking<List<Candidate>> { withExistingKeys(candidates, psiFile) }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed || !psiFile.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { found ->
                val dialog = BatchExtractDialog(project, found)
                if (dialog.showAndGet()) extract(project, editor, dialog.getSelectedCandidates())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * [candidates] with the keys already holding their text, the first one proposed instead of a
     * key derived from the text. Looked up in a single pass; needs a read action, off the EDT.
     */
    internal fun withExistingKeys(candidates: List<Candidate>, caller: PsiElement): List<Candidate> {
        val found = ExistingKeyFinder.findAll(candidates.map { it.originalText }, caller)
        return candidates.map { candidate ->
            val keys = found[candidate.originalText].orEmpty()
            candidate.copy(existingKeys = keys, proposedKey = keys.firstOrNull() ?: candidate.proposedKey)
        }
    }

    /**
     * Every string literal of [psiFile] an extractor accepts and has not extracted yet.
     *
     * The candidate is the literal's string *token*, not the [JSLiteralExpression] around it:
     * that is the element extractors are written for — `JsTranslationExtractor.canExtract` only
     * accepts `JS:STRING_LITERAL`, and its `textRange` is the token's parent. Handing them the
     * expression made the action find nothing in a JS or TS file, and would have replaced the
     * whole enclosing statement had it found something.
     */
    internal fun collectCandidates(psiFile: PsiFile): List<Candidate> {
        val extractors = Extensions.LANG.extensionList.map { it.translationExtractor() }
        return PsiTreeUtil.findChildrenOfType(psiFile, JSLiteralExpression::class.java)
            .mapNotNull { it.firstChild }
            .filter { literal ->
                extractors.any { it.canExtract(literal) && !it.isExtracted(literal) }
            }
            .map { literal ->
                val extractor = extractors.first { it.canExtract(literal) }
                val text = extractor.text(literal).trim()
                Candidate(literal = literal, originalText = text, proposedKey = toProposedKey(text))
            }
            .filter { it.originalText.isNotEmpty() }
    }

    /**
     * Replaces each of [selected] with its key, creating only the keys that do not exist yet.
     *
     * - A key the candidate already holds ([Candidate.existingKeys]) is reused: the literal is
     *   replaced and nothing is written to the translation files.
     * - The literals sharing a key — the same text twice in a file gets the same proposed key —
     *   create it once, with the first one's text, and are all replaced when it exists.
     *
     * Each replacement happens later, when its key creation completes, and every earlier one
     * shifts the text after it. The ranges are therefore tracked by [RangeMarker]s taken now,
     * before any write, rather than as offsets: an offset computed up front pointed at the wrong
     * text as soon as a preceding literal was replaced by a key of a different length.
     */
    internal fun extract(project: Project, editor: Editor, selected: List<Pair<Candidate, String>>) {
        val extractors = Extensions.LANG.extensionList.map { it.translationExtractor() }
        val config = Settings.getInstance(project).config()
        val parser = if (config.usesFlatKeys()) KeyParserBuilder.withoutTokenizer()
                     else KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator)
        val replacements = selected.map { (candidate, key) ->
            val extractor = extractors.first { it.canExtract(candidate.literal) }
            Replacement(candidate, key, extractor, editor.document.createRangeMarker(extractor.textRange(candidate.literal)))
        }
        val (reused, created) = replacements.partition { it.key in it.candidate.existingKeys }

        if (reused.isNotEmpty()) {
            WriteCommandAction.runWriteCommandAction(project, PluginBundle.message("action.batch.extract.title"), null, {
                reused.forEach { it.apply(editor, it.key) }
            })
        }
        for ((key, group) in created.groupBy { it.key }) {
            val fullKey = parser.build().parse(
                RawKey(listOf(KeyElement.literal(key))),
                emptyNamespace = config.usesFlatKeys(),
                firstComponentNamespace = config.firstComponentNs
            )
            if (fullKey == null) {
                group.forEach { it.marker.dispose() }
                continue
            }
            keyCreator.createKey(project, fullKey, group.first().candidate.originalText, editor) {
                group.forEach { it.apply(editor, fullKey.source) }
            }
        }
        editor.caretModel.primaryCaret.removeSelection()
    }

    /** A literal to replace by [key], at [marker], through its [extractor]'s template. */
    private class Replacement(
        val candidate: Candidate,
        val key: String,
        val extractor: TranslationExtractor,
        val marker: RangeMarker
    ) {
        private val template = extractor.template(candidate.literal)

        fun apply(editor: Editor, source: String) {
            if (marker.isValid) {
                editor.document.replaceString(marker.startOffset, marker.endOffset, template("'$source'"))
                extractor.postProcess(editor, marker.startOffset)
            }
            marker.dispose()
        }
    }

    private fun toProposedKey(text: String): String =
        text.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "_")
            .take(50)
            .trim('_')
}

internal data class Candidate(
    val literal: PsiElement,
    val originalText: String,
    val proposedKey: String,
    /** The keys already holding [originalText] in the reference locale; none when not looked up. */
    val existingKeys: List<String> = emptyList()
)

private class BatchExtractDialog(
    project: Project,
    private val candidates: List<Candidate>
) : DialogWrapper(project) {

    private val rows: List<Row> = candidates.map { Row(it) }

    init {
        title = PluginBundle.message("action.batch.extract.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = JPanel(GridBagLayout())
        val gc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 4, 2, 4)
        }

        rows.forEachIndexed { index, row ->
            gc.gridy = index
            gc.gridx = 0
            gc.weightx = 0.0
            content.add(row.checkBox, gc)

            gc.gridx = 1
            gc.weightx = 0.3
            content.add(JLabel(row.candidate.originalText.take(40)), gc)

            gc.gridx = 2
            gc.weightx = 0.7
            content.add(row.keyField, gc)

            gc.gridx = 3
            gc.weightx = 0.0
            content.add(row.existingLabel, gc)
        }

        val scroll = JBScrollPane(content)
        scroll.preferredSize = Dimension(680, minOf(400, rows.size * 34 + 20))
        return scroll
    }

    fun getSelectedCandidates(): List<Pair<Candidate, String>> =
        rows.filter { it.checkBox.isSelected }
            .map { it.candidate to it.keyField.text.trim() }
            .filter { (_, key) -> key.isNotEmpty() }

    private inner class Row(val candidate: Candidate) {
        val checkBox = JBCheckBox("", true)
        val keyField = JTextField(candidate.proposedKey, 30)

        /** Says the proposed key exists, and which others hold the same text. */
        val existingLabel = JLabel(
            if (candidate.existingKeys.isEmpty()) ""
            else PluginBundle.message("action.batch.extract.existing")
        ).apply {
            if (candidate.existingKeys.size > 1) toolTipText = candidate.existingKeys.joinToString("<br>", "<html>", "</html>")
        }
    }
}
