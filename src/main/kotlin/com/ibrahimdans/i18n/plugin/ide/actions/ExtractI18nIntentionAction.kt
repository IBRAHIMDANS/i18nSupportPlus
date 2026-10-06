package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
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
     * Looks for keys already holding the text before asking for a new one.
     *
     * [ExistingKeyFinder] walks the file-type index, which the platform forbids on the EDT: it runs
     * in a non-blocking read action, and the dialogs start back on the EDT, as in [KeyCreator].
     */
    private fun doInvoke(editor: Editor, project: Project, element: PsiElement) {
        val extractor = getExtractor(element)
        val text = extractor.text(element).trim()
        ReadAction.nonBlocking<List<String>> { ExistingKeyFinder.find(text, element) }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed || !element.isValid }
            .finishOnUiThread(ModalityState.defaultModalityState()) { existingKeys ->
                when (val choice = request.choose(project, text, existingKeys)) {
                    is KeyChoice.Existing -> reuseKey(editor, project, element, extractor, choice.key)
                    KeyChoice.New -> createKey(editor, project, element, extractor, text)
                    KeyChoice.Cancelled -> {}
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Replaces the literal with [key] through the same template as a created key, and writes
     * nothing to the translation files: the key already holds the text.
     */
    private fun reuseKey(editor: Editor, project: Project, element: PsiElement, extractor: TranslationExtractor, key: String) {
        val template = extractor.template(element)
        val range = extractor.textRange(element)
        WriteCommandAction.runWriteCommandAction(project, getText(), null, {
            editor.document.replaceString(range.startOffset, range.endOffset, template("'$key'"))
            extractor.postProcess(editor, range.startOffset)
        })
        editor.caretModel.primaryCaret.removeSelection()
    }

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
        val template = extractor.template(element)
        val range = extractor.textRange(element)
        keyCreator.createKey(project, i18nKey, text, editor) {
            document.replaceString(range.startOffset, range.endOffset, template("'${i18nKey.source}'"))
            extractor.postProcess(editor, range.startOffset)
        }
        editor.caretModel.primaryCaret.removeSelection()
    }

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        getExtractor(element).canExtract(element)
}

