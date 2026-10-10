package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.ExtractKeyDialog
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil

internal class DefaultExtractor : TranslationExtractor {
    override fun canExtract(element: PsiElement): Boolean = false
    override fun text(element: PsiElement): String = ""
    override fun isExtracted(element: PsiElement): Boolean = false
}

/**
 * Intention action of i18n key extraction
 */
class ExtractI18nIntentionAction : PsiElementBaseIntentionAction(), IntentionAction {

    private val request = KeyRequest()

    private val keyCreator = KeyCreator()

    override fun getText() = PluginBundle.getMessage("action.intention.extract.key")

    override fun getFamilyName() = "ExtractI18nIntentionAction"

    override fun invoke(project: Project, editor: Editor, element: PsiElement) =
        ApplicationManager.getApplication().invokeLater {
            doInvoke(editor, project, element)
        }

    private fun getExtractor(element: PsiElement): TranslationExtractor =
        Extensions.LANG
            .extensionList
            .map {it.translationExtractor()}
            .filter { it.canExtract(element) }
            .whenMatches { extractors -> !extractors.any { it.isExtracted(element) } }
            ?.firstOrNull()
            ?: DefaultExtractor()

    /**
     * Reads what the dialog shows — the keys already holding the text, the namespaces and their
     * files — then opens it, and applies the answer.
     *
     * The reading walks the file-type index, which the platform forbids on the EDT: it runs in a
     * non-blocking read action, and the dialog starts back on the EDT, as in [KeyCreator]. A
     * project without any translation file keeps the prompts that create one.
     */
    private fun doInvoke(editor: Editor, project: Project, element: PsiElement) {
        val extractor = getExtractor(element)
        val text = extractor.text(element).trim()
        ReadAction.nonBlocking<ExtractKeyModel> {
            ExtractKeyModel.load(
                project, element, text, ExistingKeyFinder.find(text, element),
                CallTemplate.call(extractor, element), extractor.variables(element), extractor.scopeNamespaces(element)
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed || !element.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { model ->
                if (model.namespaces.isEmpty()) return@finishOnUiThread createKey(editor, project, element, extractor, text)
                val range = editor.document.createRangeMarker(extractor.textRange(element))
                val answer = opener(project, model, element)
                if (answer != null) apply(editor, project, extractor, range, model, answer)
                range.dispose()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Writes the translations [answer] asks for and replaces the text with the call, in one
     * command: one Ctrl+Z undoes the extraction as a whole. [range] follows the text through any
     * edit made while the dialog was open.
     */
    private fun apply(
        editor: Editor,
        project: Project,
        extractor: TranslationExtractor,
        range: RangeMarker,
        model: ExtractKeyModel,
        answer: ExtractAnswer
    ) {
        if (!range.isValid) return
        val call = when (answer) {
            is ExtractAnswer.Reuse -> model.reusePreview(answer.key)
            is ExtractAnswer.Create -> model.preview(answer.namespace, answer.key, answer.variables)
        }
        WriteCommandAction.runWriteCommandAction(project, getText(), null, {
            if (answer is ExtractAnswer.Create) {
                val key = model.fullKey(answer.namespace, answer.key)
                val viewModel = DialogViewModel(project)
                model.writes(answer).forEach { write ->
                    val written = if (write.suffix.isEmpty()) key else model.fullKey(answer.namespace, answer.key + write.suffix)
                    viewModel.saveTranslation(write.source, written, write.value, write.overwrite)
                }
            }
            editor.document.replaceString(range.startOffset, range.endOffset, call)
            extractor.postProcess(editor, range.startOffset)
        })
        editor.caretModel.primaryCaret.removeSelection()
    }

    /**
     * The prompts of a project without any translation file: a key, then the file it creates —
     * see [KeyCreator].
     */
    private fun createKey(editor: Editor, project: Project, element: PsiElement, extractor: TranslationExtractor, text: String) {
        val document = editor.document
        val requestResult = request.key(project, text)
        if (requestResult.isCancelled) return
        if (requestResult.key == null) {
            Messages.showInfoMessage(
                PluginBundle.getMessage("action.intention.extract.key.invalid"),
                PluginBundle.getMessage("action.intention.extract.key.invalid.title")
            )
            return
        }
        val i18nKey = requestResult.key
        val template = CallTemplate.template(extractor, element)
        val range = extractor.textRange(element)
        keyCreator.createKey(project, i18nKey, text, editor) {
            document.replaceString(range.startOffset, range.endOffset, template("'${i18nKey.source}'"))
            extractor.postProcess(editor, range.startOffset)
        }
        editor.caretModel.primaryCaret.removeSelection()
    }

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        getExtractor(element).canExtract(element)

    companion object {
        /**
         * Shows the dialog and returns its answer, null when cancelled. A test swaps it for a
         * scripted answer: a `DialogWrapper` cannot be shown in a test container.
         */
        internal var opener: (Project, ExtractKeyModel, PsiElement) -> ExtractAnswer? = { project, model, element ->
            ExtractKeyDialog(project, model, element).let { if (it.showAndGet()) it.answer else null }
        }
    }
}

