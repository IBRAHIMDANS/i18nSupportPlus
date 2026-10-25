package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager

/**
 * The locale the editor previews translations in, and how it is switched from the editor.
 *
 * Changing the preview locale meant a trip through *Settings*: going from `fr` to `en` to read
 * a screen in the other language was six clicks. [switchTo] writes it — everything inline
 * (inlay hints, hover, Ctrl+click, folding) then reads in that locale — and restarts the daemon
 * so the open editors follow.
 *
 * Only the preview locale: *Preferred folding language* is also the project's reference locale
 * ([com.ibrahimdans.i18n.plugin.utils.ReferenceLocale]), and writing it here made a glance at
 * another locale change what every other locale is compared against.
 */
object PreviewLocaleSwitcher {

    const val WIDGET_ID = "com.ibrahimdans.i18n.previewLocale"

    /** The locale inline features show under [config]: the preview locale, or the folding language when none is set. */
    fun effective(config: Config): String = config.previewLocale.ifBlank { config.foldingPreferredLanguage }

    /**
     * The locale after [current] in [locales], wrapping around; the first one when [current] is
     * not among them, and [current] itself when there is nothing to cycle through.
     */
    fun next(current: String, locales: List<String>): String {
        if (locales.isEmpty()) return current
        val index = locales.indexOf(current)
        return if (index < 0) locales.first() else locales[(index + 1) % locales.size]
    }

    /** Makes [locale] the preview locale of [project], and refreshes the editors and the status bar. */
    fun switchTo(project: Project, locale: String) {
        val settings = Settings.getInstance(project)
        settings.previewLocale = locale
        EditorRefresh.afterSettingsChange(project)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
        }
    }
}
