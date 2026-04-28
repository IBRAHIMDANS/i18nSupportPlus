package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateTranslationFileQuickFix
import com.ibrahimdans.i18n.plugin.ide.quickfix.UserChoice
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Extracts translation key
 */
class KeyCreator {

    fun createKey(project:Project, i18nKey: FullKey, source: String, editor:Editor, onComplete: () -> Unit) {
        val sourceService = project.service<LocalizationSourceService>()
        val settings = Settings.getInstance(project)
        val config = settings.config()
        val files = sourceService.findSources(i18nKey.allNamespaces(), project)
        val quickFix = if (files.isEmpty()) {
            val contentGenerator = Extensions.LOCALIZATION.extensionList.find {
                it.config().id() == config.preferredLocalization
            }?.contentGenerator()
            val fileName = i18nKey.ns?.text ?: config.defaultNamespaces().firstOrNull() ?: "common"
            contentGenerator?.let{CreateTranslationFileQuickFix(i18nKey, it, fileName, source, onComplete)}
        } else {
            CreateKeyQuickFix(i18nKey, UserChoice(), PluginBundle.getMessage("quickfix.create.key"), source, onComplete)
        }
        quickFix?.invoke(project, editor)
    }
}
