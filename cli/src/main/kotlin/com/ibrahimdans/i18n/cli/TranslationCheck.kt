package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.check.LocaleCheck

/** What one locale gets wrong compared to the reference: [LocaleCheck] compares, in `core`, for the IDE as well. */
typealias LocaleProblems = com.ibrahimdans.i18n.plugin.check.LocaleProblems

/**
 * The problems of every locale, sorted by locale, compared to [reference], in [module] — null for
 * the whole project — and, when the code was checked, the [unused] keys of the reference; the
 * [files] the locales were read from, for the reports that point at them.
 */
data class CheckResult(
    val reference: String,
    val locales: List<LocaleProblems>,
    val module: String? = null,
    val unused: List<String>? = null,
    val files: List<LocaleFile> = emptyList(),
) {
    val problems: Int get() = locales.sumOf { it.count } + unused.orEmpty().size
}

/** What a run found: a [CheckResult] per module, and when the code was checked, its [unknown] keys. */
data class ProjectResult(val modules: List<CheckResult>, val unknown: List<UnknownKey>? = null) {
    val problems: Int get() = modules.sumOf { it.problems } + unknown.orEmpty().size
}

/**
 * The check itself, on the key -> locale -> value map of a project's translations: wherever they
 * were read from, a directory ([LocaleDirectory]) or a whole project ([ProjectSources]). The
 * comparison is [LocaleCheck]'s, shared with the IDE.
 */
object TranslationCheck {

    /** [pluralLocale] gives the locale whose plural forms a label needs: an alias's target (`gb` -> `en-GB`). */
    fun run(translations: Map<String, Map<String, String>>, reference: String, pluralLocale: (String) -> String = { it }): CheckResult =
        CheckResult(reference, LocaleCheck.run(translations, reference, pluralLocale))
}
