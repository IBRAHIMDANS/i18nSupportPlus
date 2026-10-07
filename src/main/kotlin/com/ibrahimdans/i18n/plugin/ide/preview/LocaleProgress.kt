package com.ibrahimdans.i18n.plugin.ide.preview

import com.ibrahimdans.i18n.plugin.ide.toolwindow.LocaleStats
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import kotlin.math.floor

/**
 * What the preview-locale widget says about translation coverage: `i18n: fr · 94 %`, and every
 * locale's rate in its tooltip. Only the Stats tab showed it before, behind a tool window.
 *
 * Pure: the widget computes the [LocaleStats] off the EDT and hands them over, so the wording is
 * tested without a status bar.
 */
internal object LocaleProgress {

    /**
     * The rate shown for [stats]: rounded down, so a locale missing one key out of a thousand
     * reads `99 %`, never a `100 %` that is not complete.
     */
    fun percentOf(stats: LocaleStats): Int = floor(stats.percent).toInt()

    /**
     * The widget's text for the [setting] locale: its rate appended when [stats] know a locale it
     * designates (`en` finds `en-GB`), the locale alone otherwise — before the first computation,
     * or for a locale no file holds.
     */
    fun text(setting: String, stats: List<LocaleStats>): String {
        val label = LocaleMatching.pick(setting, stats.map { it.locale })
        val current = stats.firstOrNull { it.locale == label }
            ?: return PluginBundle.message("preview.locale.widget.text", setting)
        return PluginBundle.message("preview.locale.widget.text.progress", setting, percentOf(current))
    }

    /** The widget's tooltip: what the widget is for, then each locale with its rate, in [stats] order. */
    fun tooltip(stats: List<LocaleStats>): String {
        val base = PluginBundle.message("preview.locale.widget.tooltip")
        if (stats.isEmpty()) return base
        val lines = stats.joinToString("<br/>") {
            PluginBundle.message("preview.locale.widget.tooltip.locale", it.locale, percentOf(it), it.translated, it.total)
        }
        return "<html>$base<br/><br/>$lines</html>"
    }
}
