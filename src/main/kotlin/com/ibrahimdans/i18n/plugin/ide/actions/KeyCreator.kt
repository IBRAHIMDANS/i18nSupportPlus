package com.ibrahimdans.i18n.plugin.ide.actions

import com.intellij.psi.PsiDocumentManager
import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateTranslationFileQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.QuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.UserChoice
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Extracts translation key
 */
class KeyCreator {

    /**
     * Whether a translation file already exists decides between creating the key and creating
     * the file. Finding out walks the file-type index, which the platform forbids on the EDT as a
     * slow operation — and both extraction actions call this from the EDT. The lookup runs in
     * the background; the chosen quick fix, with its dialogs and writes, starts back on the EDT.
     */
    fun createKey(project:Project, i18nKey: FullKey, source: String, editor:Editor, onComplete: () -> Unit) {
        ReadAction.nonBlocking<Boolean> {
            // Asked in the caller's module, like the quick fix that writes the key.
            val service = project.service<LocalizationSourceService>()
            val caller = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            (if (caller != null) service.findSources(i18nKey.allNamespaces(), caller)
            else service.findSources(i18nKey.allNamespaces(), project)).isNotEmpty()
        }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { editor.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState()) { hasSources ->
                quickFix(project, i18nKey, source, hasSources, onComplete)?.invoke(project, editor)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun quickFix(project: Project, i18nKey: FullKey, source: String, hasSources: Boolean, onComplete: () -> Unit): QuickFix? {
        if (hasSources) {
            return CreateKeyQuickFix(i18nKey, UserChoice(), PluginBundle.getMessage("quickfix.create.key"), source, onComplete)
        }
        val config = Settings.getInstance(project).config()
        val contentGenerator = Extensions.LOCALIZATION.extensionList.find {
            it.config().id() == config.preferredLocalization
        }?.contentGenerator()
        val fileName = i18nKey.ns?.text ?: config.defaultNamespaces().firstOrNull() ?: "common"
        return contentGenerator?.let { CreateTranslationFileQuickFix(i18nKey, it, fileName, source, onComplete) }
    }
}
