package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.tree.PluralKey

/** What is wrong with the value of [key] in a locale, in a sentence. */
data class ValueProblem(val key: String, val message: String)

/** An ICU `plural` or `select` block of type [block] that lacks a form. */
data class IcuIssue(val block: String, val lacking: Lacking) {
    enum class Lacking { OTHER, ONE_OR_ZERO }
}

/**
 * The checks on what a translation says rather than which keys it has: placeholders, tags and ICU
 * syntax. The inspections of the IDE and `i18n-check` call the same rules, so a value one accepts
 * the other accepts too; [run] is the whole pass, for a tool reading a project at once.
 */
object ValueCheck {

    private val PLACEHOLDER = Regex("""\{[\w\d_]+\}|%[0-9]*\$?[sd]""")
    private val TAG = Regex("""<(/?)([A-Za-z0-9_.-]+)(?:\s[^<>]*?)?\s*(/?)>""")
    private val ICU_BLOCK = Regex("""\{\s*\w+\s*,\s*(plural|select)\s*,([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""")
    private val ICU_FORM = Regex("""(\w+)\s*\{""")

    /** i18next's variable for the plural count: a form may well spell the number or leave it out. */
    private const val PLURAL_COUNT = "{count}"

    /**
     * Languages whose only CLDR cardinal category is `other`: a plural in them carries no `one` or
     * `zero` form, and asking for one is a false warning on every Japanese or Chinese catalogue.
     */
    private val OTHER_ONLY_LANGUAGES = setOf(
        "bo", "dz", "id", "ig", "ii", "ja", "jbo", "jv", "kde", "kea", "km", "ko", "lkt", "lo", "ms",
        "my", "nqo", "osa", "sah", "ses", "sg", "su", "th", "to", "tpi", "vi", "wo", "yo", "yue", "zh"
    )

    /** Whether each `{` of [text] closes, on any line: an ICU message often spans several. */
    fun balanced(text: String): Boolean {
        var depth = 0
        for (ch in text) {
            when (ch) {
                '{' -> depth++
                '}' -> if (--depth < 0) return false
            }
        }
        return depth == 0
    }

    private fun placeholdersOf(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.value }.toSet()

    /**
     * The placeholders of [reference] that [value] lacks. A value with no placeholder at all is left
     * alone: the translator may have written the variable out on purpose.
     */
    fun missingPlaceholders(value: String, reference: String): Set<String> {
        val placeholders = placeholdersOf(value)
        return if (placeholders.isEmpty()) emptySet() else placeholdersOf(reference) - placeholders
    }

    /** The placeholders of [value] that [reference] does not have. */
    fun extraPlaceholders(value: String, reference: String): Set<String> =
        placeholdersOf(value) - placeholdersOf(reference)

    /** The tags of [text], normalised (`<1>`, `</1>`, `<br/>`), with how many times each appears. */
    private fun tagsOf(text: String): Map<String, Int> =
        TAG.findAll(text)
            .map { match ->
                val (closing, name, selfClosing) = match.destructured
                if (selfClosing.isNotEmpty()) "<$name/>" else "<$closing$name>"
            }
            .groupingBy { it }
            .eachCount()

    /** The tags [this] holds more often than [other]. */
    private fun Map<String, Int>.beyond(other: Map<String, Int>): Set<String> =
        filter { (tag, count) -> count > (other[tag] ?: 0) }.keys

    /** The tags of [reference] that [value] lacks; their order is free, as a language may reorder them. */
    fun missingTags(value: String, reference: String): Set<String> = tagsOf(reference).beyond(tagsOf(value))

    /** The tags of [value] that [reference] does not have. */
    fun extraTags(value: String, reference: String): Set<String> = tagsOf(value).beyond(tagsOf(reference))

    /** Whether a `plural` block written in [locale] (`fr`, `pt-BR`) must hold a `one` or `zero` form. */
    fun pluralNeedsOne(locale: String): Boolean =
        locale.substringBefore('-').substringBefore('_').lowercase() !in OTHER_ONLY_LANGUAGES

    /** The forms the ICU blocks of the balanced [value] lack. */
    fun icuIssues(value: String, needsOne: Boolean): List<IcuIssue> = buildList {
        for (match in ICU_BLOCK.findAll(value)) {
            val (block, body) = match.destructured
            val forms = ICU_FORM.findAll(body).map { it.groupValues[1] }.toSet()
            if ("other" !in forms) add(IcuIssue(block, IcuIssue.Lacking.OTHER))
            if (block == "plural" && needsOne && "one" !in forms && "zero" !in forms) {
                add(IcuIssue(block, IcuIssue.Lacking.ONE_OR_ZERO))
            }
        }
    }

    /**
     * The problems of each locale's values, sorted by key, on the key -> locale -> value map of a
     * project's translations; a blank value is left to [LocaleCheck], which calls it empty.
     * [pluralLocale] gives the locale whose plural forms a label needs: an alias's target (`gb` -> `en-GB`).
     */
    fun run(
        translations: Map<String, Map<String, String>>,
        reference: String,
        pluralLocale: (String) -> String = { it },
    ): Map<String, List<ValueProblem>> {
        val pluralForms = PluralKey.groupForms(translations.keys)
            .filter { (base, forms) -> forms != listOf(base) }
            .values.flatten().toSet()
        val keys = translations.keys.sorted()
        return translations.values.flatMap { it.keys }.distinct().sorted().associateWith { locale ->
            keys.flatMap { key ->
                val values = translations.getValue(key)
                val value = values[locale]?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
                val compared = values[reference]?.takeIf { locale != reference && it.isNotBlank() && balanced(it) }
                messages(value, compared, pluralNeedsOne(pluralLocale(locale)), key in pluralForms)
                    .map { ValueProblem(key, it) }
            }
        }
    }

    /** What is wrong with [value], compared to [reference] when there is one to compare it to. */
    private fun messages(value: String, reference: String?, needsOne: Boolean, pluralForm: Boolean): List<String> {
        if (!balanced(value)) return listOf("Unbalanced braces")
        return buildList {
            icuIssues(value, needsOne).forEach {
                add(
                    when (it.lacking) {
                        IcuIssue.Lacking.OTHER -> "ICU '${it.block}' block without the required 'other' form"
                        IcuIssue.Lacking.ONE_OR_ZERO -> "ICU 'plural' block without a 'one' or 'zero' form"
                    },
                )
            }
            if (reference == null) return@buildList
            val ignored = if (pluralForm) setOf(PLURAL_COUNT) else emptySet()
            (missingPlaceholders(value, reference) - ignored).forEach { add("Placeholder '$it' of the reference locale is missing") }
            (extraPlaceholders(value, reference) - ignored).forEach { add("Placeholder '$it' is not in the reference locale") }
            missingTags(value, reference).forEach { add("Tag '$it' of the reference locale is missing") }
            extraTags(value, reference).forEach { add("Tag '$it' is not in the reference locale") }
        }
    }
}
