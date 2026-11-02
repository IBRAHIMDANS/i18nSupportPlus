package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey

/** What is wrong with the value of [key] in a locale, in a sentence. */
data class ValueProblem(val key: String, val message: String)

/**
 * An ICU `plural` or `select` block of type [block] without its `other` form, the only one ICU
 * requires: `{n, plural, other {# items}}` and `{n, plural, =0 {…} other {…}}` are complete.
 */
data class IcuIssue(val block: String)

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
    private val ICU_ARGUMENT = Regex("""\{\s*\w+\s*,\s*(?:plural|select|selectordinal)\s*,""")
    private val WORD = Regex("""\w+""")

    /** i18next's variable for the plural count: a form may well spell the number or leave it out. */
    private const val PLURAL_COUNT = "{count}"

    /** The `other` form of an i18next plural group, the one category every language has. */
    private const val OTHER_FORM = "_${PluralCategories.OTHER}"

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

    /**
     * The placeholders of [text], without the body of an ICU form that is a single word: `none` of
     * `{n, plural, =0 {none} other {# items}}` is text to translate, not a variable.
     */
    private fun placeholdersOf(text: String): Set<String> {
        val wordForms = wordFormBodies(text)
        return PLACEHOLDER.findAll(text).filter { it.range !in wordForms }.map { it.value }.toSet()
    }

    /** The ranges, braces included, of the one-word form bodies of each ICU block of [text]. */
    private fun wordFormBodies(text: String): Set<IntRange> =
        ICU_ARGUMENT.findAll(text).flatMap { wordFormBodies(text, it.range.last + 1) }.toSet()

    /** The one-word form bodies of the ICU block whose forms start at [from], up to its closing brace. */
    private fun wordFormBodies(text: String, from: Int): List<IntRange> {
        val bodies = mutableListOf<IntRange>()
        var depth = 0
        var bodyStart = -1
        for (i in from until text.length) {
            when (text[i]) {
                '{' -> if (depth++ == 0) bodyStart = i
                '}' -> {
                    if (depth == 0) break
                    if (--depth == 0 && WORD.matches(text.substring(bodyStart + 1, i))) bodies += bodyStart..i
                }
            }
        }
        return bodies
    }

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

    /** The ICU blocks of the balanced [value] that lack their `other` form. */
    fun icuIssues(value: String): List<IcuIssue> =
        ICU_BLOCK.findAll(value)
            .filter { match -> ICU_FORM.findAll(match.groupValues[2]).none { it.groupValues[1] == PluralCategories.OTHER } }
            .map { IcuIssue(it.groupValues[1]) }
            .toList()

    /** The base of [key] when it ends like an i18next plural form (`item` for `item_few`), else null. */
    private fun pluralBase(key: String): String? =
        key.substringBeforeLast('_', "").takeIf { it.isNotEmpty() && key.substringAfterLast('_') in PluralCategories.ALL }

    /**
     * The key of the reference locale [key]'s value is compared to: [key] itself, or, for a plural
     * form the reference does not have — `item_few` of Russian against English's `item_one` and
     * `item_other` — the `_other` form of its group. Null when the reference holds neither.
     */
    fun referenceKeyOf(key: String, inReference: (String) -> Boolean): String? = when {
        inReference(key) -> key
        else -> pluralBase(key)?.let { it + OTHER_FORM }?.takeIf(inReference)
    }

    /**
     * Whether [key] is a form of a plural group whose `_other` form [holds] finds: such a form may
     * spell the count out or leave it, so `{count}` is not compared on it.
     */
    fun isPluralForm(key: String, holds: (String) -> Boolean): Boolean =
        pluralBase(key)?.let { holds(it + OTHER_FORM) } == true

    /** [placeholders] without `{count}` when they belong to a plural form. */
    fun comparedPlaceholders(placeholders: Set<String>, pluralForm: Boolean): Set<String> =
        if (pluralForm) placeholders - PLURAL_COUNT else placeholders

    /**
     * The problems of each locale's values, sorted by key, on the key -> locale -> value map of a
     * project's translations; a blank value is left to [LocaleCheck], which calls it empty. A plural
     * form the reference lacks is compared to the reference's `_other` form ([referenceKeyOf]).
     *
     * [pluralLocale] is no longer read: a `plural` block only requires `other`, whatever the
     * language. It is kept so that `i18n-check`'s call stays as it is; drop it with the next change there.
     */
    fun run(
        translations: Map<String, Map<String, String>>,
        reference: String,
        @Suppress("UNUSED_PARAMETER") pluralLocale: (String) -> String = { it },
    ): Map<String, List<ValueProblem>> {
        val pluralForms = PluralKey.groupForms(translations.keys)
            .filter { (base, forms) -> forms != listOf(base) }
            .values.flatten().toSet()
        val inReference = { key: String -> translations[key]?.get(reference)?.isNotBlank() == true }
        val keys = translations.keys.sorted()
        return translations.values.flatMap { it.keys }.distinct().sorted().associateWith { locale ->
            keys.flatMap { key ->
                val values = translations.getValue(key)
                val value = values[locale]?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
                val pluralForm = key in pluralForms
                val referenceKey = if (pluralForm) referenceKeyOf(key, inReference) else key
                val compared = referenceKey?.let { translations[it]?.get(reference) }
                    ?.takeIf { locale != reference && it.isNotBlank() && balanced(it) }
                messages(value, compared, pluralForm).map { ValueProblem(key, it) }
            }
        }
    }

    /** What is wrong with [value], compared to [reference] when there is one to compare it to. */
    private fun messages(value: String, reference: String?, pluralForm: Boolean): List<String> {
        if (!balanced(value)) return listOf("Unbalanced braces")
        return buildList {
            icuIssues(value).forEach { add("ICU '${it.block}' block without the required 'other' form") }
            if (reference == null) return@buildList
            comparedPlaceholders(missingPlaceholders(value, reference), pluralForm)
                .forEach { add("Placeholder '$it' of the reference locale is missing") }
            comparedPlaceholders(extraPlaceholders(value, reference), pluralForm)
                .forEach { add("Placeholder '$it' is not in the reference locale") }
            missingTags(value, reference).forEach { add("Tag '$it' of the reference locale is missing") }
            extraTags(value, reference).forEach { add("Tag '$it' is not in the reference locale") }
        }
    }
}
