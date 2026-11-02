package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.check.LocaleCheck
import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.check.ValueProblem
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
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
 *
 * A Laravel package's override (`courier::messages:sent`, read from `lang/vendor/courier/en/messages.php`)
 * is partial by nature: a locale holding no override of that package keeps the package's own translation,
 * shipped under Composer's `vendor/`. Its keys are missing only in a locale that overrides the package
 * file and leaves one of them out.
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
        withoutUnoverriddenPackages(LocaleCheck.run(translations, reference, pluralLocale, parentLocale), translations, parentLocale),
        values = if (values) ValueCheck.run(translations, reference, pluralLocale) else null,
    )

    /** [problems] without the package keys missing in a locale that overrides nothing of their package, nor does its parent. */
    private fun withoutUnoverriddenPackages(
        problems: List<LocaleProblems>,
        translations: Map<String, Map<String, String>>,
        parentLocale: (String) -> String?,
    ): List<LocaleProblems> {
        val overriddenIn = mutableMapOf<String, MutableSet<String>>()
        for ((key, values) in translations) {
            val namespace = packageNamespaceOf(key) ?: continue
            overriddenIn.getOrPut(namespace) { mutableSetOf() } += values.keys
        }
        if (overriddenIn.isEmpty()) return problems
        return problems.map { locale ->
            val holders = setOfNotNull(locale.locale, parentLocale(locale.locale))
            locale.copy(missing = locale.missing.filter { key ->
                val namespace = packageNamespaceOf(key) ?: return@filter true
                overriddenIn[namespace].orEmpty().any { it in holders }
            })
        }
    }

    /** The package namespace of [key], `courier::messages` for `courier::messages:sent`; null for any other key. */
    private fun packageNamespaceOf(key: String): String? =
        KeySpelling.namespaceOf(key)?.takeIf { PACKAGE_SEPARATOR in it }

    private const val PACKAGE_SEPARATOR = "::"
}
