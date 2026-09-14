package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * `i18n: en` in the status bar; a click lists the project's locales and switches to the one
 * picked — see [PreviewLocaleSwitcher].
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

    override fun ID(): String = PreviewLocaleSwitcher.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        refreshLocales()
    }

    override fun getSelectedValue(): String =
        PluginBundle.message("preview.locale.widget.text", PreviewLocaleSwitcher.effective(Settings.getInstance(project).config()))

    override fun getTooltipText(): String = PluginBundle.message("preview.locale.widget.tooltip")

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
        ReadAction.nonBlocking<List<String>> { TranslationDataLoader.discoverLocales(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { locales = it }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
