package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.check.LocaleCheck
import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.check.ValueProblem
import com.ibrahimdans.i18n.plugin.utils.LocaleParent

/** What one locale gets wrong compared to the reference: [LocaleCheck] compares, in `core`, for the IDE as well. */
typealias LocaleProblems = com.ibrahimdans.i18n.plugin.check.LocaleProblems

/**
 * The problems of every locale, sorted by locale, compared to [reference], in [module] — null for
 * the whole project — and, when the code was checked, the [unused] keys of the reference; the
 * [files] the locales were read from, for the reports that point at them; and, unless `--no-values`
 * turned them off, the [values] each locale gets wrong.
 */
data class CheckResult(
    val reference: String,
    val locales: List<LocaleProblems>,
    val module: String? = null,
    val unused: List<String>? = null,
    val files: List<LocaleFile> = emptyList(),
    val values: Map<String, List<ValueProblem>>? = null,
) {
    val problems: Int get() = locales.sumOf { it.count } + unused.orEmpty().size + values.orEmpty().values.sumOf { it.size }
}

/** What a run found: a [CheckResult] per module, and when the code was checked, its [unknown] keys. */
data class ProjectResult(val modules: List<CheckResult>, val unknown: List<UnknownKey>? = null) {
    val problems: Int get() = modules.sumOf { it.problems } + unknown.orEmpty().size
}

/**
 * The check itself, on the key -> locale -> value map of a project's translations: wherever they
 * were read from, a directory ([LocaleDirectory]) or a whole project ([ProjectSources]). The
 * comparison is [LocaleCheck]'s and the checks on values are [ValueCheck]'s, shared with the IDE.
 */
object TranslationCheck {

    /**
     * [pluralLocale] gives the locale whose plural forms a label needs: an alias's target (`gb` -> `en-GB`).
     * [parentLocale] gives the locale a label inherits the keys it lacks from, null for none.
     * [values] also checks the placeholders, tags and ICU of each value.
     */
    fun run(
        translations: Map<String, Map<String, String>>,
        reference: String,
        pluralLocale: (String) -> String = { it },
        parentLocale: (String) -> String? = LocaleParent::of,
        values: Boolean = true,
    ): CheckResult = CheckResult(
        reference,
        LocaleCheck.run(translations, reference, pluralLocale, parentLocale),
        values = if (values) ValueCheck.run(translations, reference, pluralLocale) else null,
    )
}
