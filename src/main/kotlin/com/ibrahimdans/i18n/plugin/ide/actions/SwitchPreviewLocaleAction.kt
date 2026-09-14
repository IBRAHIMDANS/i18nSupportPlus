package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.preview.PreviewLocaleSwitcher
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * *Switch Preview Locale*: cycles the locale the editor previews translations in through the
 * project's locales — `en` → `fr` → `en` — without opening the settings. The status bar widget
 * offers the same switch as a list; this action is the one a keyboard shortcut can be bound to.
 *
 * Text and description come from `plugin.xml`, resolved against the plugin's bundle.
 */
class SwitchPreviewLocaleAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val current = PreviewLocaleSwitcher.effective(Settings.getInstance(project).config())
        ReadAction.nonBlocking<List<String>> { TranslationDataLoader.discoverLocales(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { locales ->
                val next = PreviewLocaleSwitcher.next(current, locales)
                if (next != current) PreviewLocaleSwitcher.switchTo(project, next)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
