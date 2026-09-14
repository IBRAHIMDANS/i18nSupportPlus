package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
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

        ApplicationManager.getApplication().invokeLater {
            val dialog = BatchExtractDialog(project, candidates)
            if (!dialog.showAndGet()) return@invokeLater
            extract(project, editor, dialog.getSelectedCandidates())
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
     * Creates a key for each of [selected] and replaces its literal once the key exists.
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
        for ((candidate, keyStr) in selected) {
            val extractor = extractors.first { it.canExtract(candidate.literal) }
            val fullKey = parser.build().parse(
                RawKey(listOf(KeyElement.literal(keyStr))),
                emptyNamespace = config.usesFlatKeys(),
                firstComponentNamespace = config.firstComponentNs
            ) ?: continue

            val template = extractor.template(candidate.literal)
            val marker = editor.document.createRangeMarker(extractor.textRange(candidate.literal))
            keyCreator.createKey(project, fullKey, candidate.originalText, editor) {
                if (marker.isValid) {
                    editor.document.replaceString(marker.startOffset, marker.endOffset, template("'${fullKey.source}'"))
                    extractor.postProcess(editor, marker.startOffset)
                }
                marker.dispose()
            }
        }
        editor.caretModel.primaryCaret.removeSelection()
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
    val proposedKey: String
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
    }
}
