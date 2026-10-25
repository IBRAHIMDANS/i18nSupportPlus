package com.ibrahimdans.i18n.extract

import com.ibrahimdans.i18n.extract.angular.ngxTranslateExtractors
import com.ibrahimdans.i18n.extract.angular.translocoExtractors
import com.ibrahimdans.i18n.extract.i18next.i18nextExtractors
import com.ibrahimdans.i18n.extract.nextintl.NextIntlKeyExtractor
import com.ibrahimdans.i18n.extract.vuei18n.vueI18nExtractors
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor

/**
 * The extractors a module's code is read with, chosen by its preset as the plugin's `ModulePresets`
 * restricts the calls it recognises: without a preset every framework's calls are read, and another
 * framework's preset is read as i18next.
 */
object CodeExtractors {

    const val NEXT_INTL = "next-intl"
    const val VUE_I18N = "vue-i18n"
    const val TRANSLOCO = "transloco"
    const val NGX_TRANSLATE = "ngx-translate"

    /**
     * A new set of extractors for [preset]: each keeps what it learnt of the file it reads, so one set per reader.
     * Transloco's come before i18next's: a `t` under `*transloco="let t; read: 'admin'"` takes its prefix.
     */
    fun forPreset(preset: String?): List<CodeKeyExtractor> = when (preset) {
        null -> translocoExtractors() + ngxTranslateExtractors() + listOf(NextIntlKeyExtractor()) + vueI18nExtractors() + i18nextExtractors()
        // next-intl's technology publishes `t`: a `t` bound by no hook is still read.
        NEXT_INTL -> listOf(NextIntlKeyExtractor()) + i18nextExtractors(setOf("t"))
        VUE_I18N -> vueI18nExtractors()
        // The `t` of `*transloco="let t"` without a prefix is read as written.
        TRANSLOCO -> translocoExtractors() + i18nextExtractors(setOf("t"))
        NGX_TRANSLATE -> ngxTranslateExtractors()
        else -> i18nextExtractors()
    }
}
