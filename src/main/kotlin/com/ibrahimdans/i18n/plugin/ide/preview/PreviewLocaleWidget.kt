package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.AlarmRefreshScheduler
import com.ibrahimdans.i18n.plugin.ide.toolwindow.LocaleStats
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationChangeWatcher
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationSourceMatcher
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationStatsAnalyzer
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * `i18n: fr · 94 %` in the status bar — the preview locale and how much of it is translated,
 * every locale's rate in the tooltip ([LocaleProgress]); a click lists the project's locales and
 * switches to the one picked — see [PreviewLocaleSwitcher].
 */
class PreviewLocaleWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = PreviewLocaleSwitcher.WIDGET_ID

    override fun getDisplayName(): String = PluginBundle.message("preview.locale.widget.name")

    override fun createWidget(project: Project): StatusBarWidget = PreviewLocaleWidget(project)
}

class PreviewLocaleWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    /**
     * The locales the popup offers, read in the background: finding them walks the file index,
     * which the popup — built on the EDT — may not. Refreshed each time the widget is shown or
     * a locale is picked, so a locale added to the project appears at the next click.
     */
    @Volatile
    private var locales: List<String> = emptyList()

    /**
     * The coverage of every locale of the project, computed with the locales: it reads every
     * translation file, so never on the EDT, and only again when one of them changes.
     */
    @Volatile
    private var stats: List<LocaleStats> = emptyList()

    private var statusBar: StatusBar? = null

    override fun ID(): String = PreviewLocaleSwitcher.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        refreshLocales()
        watchTranslationFiles()
    }

    override fun getSelectedValue(): String =
        LocaleProgress.text(PreviewLocaleSwitcher.effective(Settings.getInstance(project).config()), stats)

    override fun getTooltipText(): String = LocaleProgress.tooltip(stats)

    override fun getPopup(): JBPopup? {
        val setting = PreviewLocaleSwitcher.effective(Settings.getInstance(project).config())
        // What the editor shows for the setting — `en-GB` for `en` in a project without a plain
        // `en` — is the entry selected; a setting matching nothing is offered as it is, so the
        // list never hides what the editor is set to.
        val current = LocaleMatching.pick(setting, locales) ?: setting
        val choices = (locales + current).distinct()
        return JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle(PluginBundle.message("preview.locale.popup.title"))
            .setSelectedValue(current, true)
            .setItemChosenCallback { chosen ->
                PreviewLocaleSwitcher.switchTo(project, chosen)
                refreshLocales()
            }
            .createPopup()
    }

    private fun refreshLocales() {
        ReadAction.nonBlocking<Pair<List<String>, List<LocaleStats>>> {
            TranslationDataLoader.discoverLocales(project) to TranslationStatsAnalyzer.analyze(project)
        }
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { (found, coverage) ->
                locales = found
                stats = coverage
                statusBar?.updateWidget(ID())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Recomputes the rates when a translation file changes, as the tool window reloads: the
     * same [TranslationSourceMatcher] decides what is a translation file, and a burst of
     * keystrokes is grouped into one computation.
     */
    private fun watchTranslationFiles() {
        val matcher = TranslationSourceMatcher(project)
        matcher.rememberDisplayedSources()
        val scheduler = AlarmRefreshScheduler(this, TranslationChangeWatcher.DEFAULT_DEBOUNCE_MS)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (!matcher.matchesAny(events.toList())) return
                scheduler.schedule {
                    refreshLocales()
                    matcher.rememberDisplayedSources()
                }
            }
        })
    }
}
