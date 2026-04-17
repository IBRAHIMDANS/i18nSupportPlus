package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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

        val extractors = Extensions.LANG.extensionList.map { it.translationExtractor() }

        val candidates = PsiTreeUtil.findChildrenOfType(psiFile, JSLiteralExpression::class.java)
            .filter { literal ->
                extractors.any { it.canExtract(literal) && !it.isExtracted(literal) }
            }
            .map { literal ->
                val extractor = extractors.first { it.canExtract(literal) }
                val text = extractor.text(literal).trim()
                Candidate(literal = literal, originalText = text, proposedKey = toProposedKey(text))
            }
            .filter { it.originalText.isNotEmpty() }

        if (candidates.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val dialog = BatchExtractDialog(project, candidates)
            if (!dialog.showAndGet()) return@invokeLater

            val selected = dialog.getSelectedCandidates()
            for ((candidate, keyStr) in selected) {
                val extractor = extractors.first { it.canExtract(candidate.literal) }
                val config = Settings.getInstance(project).config()
                val parser = if (config.gettext) KeyParserBuilder.withoutTokenizer()
                             else KeyParserBuilder.withSeparators(config.nsSeparator, config.keySeparator)
                val fullKey = parser.build().parse(
                    RawKey(listOf(KeyElement.literal(keyStr))),
                    emptyNamespace = config.gettext,
                    firstComponentNamespace = config.firstComponentNs
                ) ?: continue

                val template = extractor.template(candidate.literal)
                val range = extractor.textRange(candidate.literal)
                keyCreator.createKey(project, fullKey, candidate.originalText, editor) {
                    editor.document.replaceString(
                        range.startOffset,
                        range.endOffset,
                        template("'${fullKey.source}'")
                    )
                    extractor.postProcess(editor, range.startOffset)
                }
            }
            editor.caretModel.primaryCaret.removeSelection()
        }
    }

    private fun toProposedKey(text: String): String =
        text.lowercase().trim()
            .replace(Regex("[^a-z0-9]+"), "_")
            .take(50)
            .trim('_')
}

private data class Candidate(
    val literal: JSLiteralExpression,
    val originalText: String,
    val proposedKey: String
)

private class BatchExtractDialog(
    project: Project,
    private val candidates: List<Candidate>
) : DialogWrapper(project) {

    private val rows: List<Row> = candidates.map { Row(it) }

    init {
        title = "Batch Extract i18n Keys"
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
