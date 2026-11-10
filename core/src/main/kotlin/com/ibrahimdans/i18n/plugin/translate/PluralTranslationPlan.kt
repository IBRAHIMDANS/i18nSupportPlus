package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.IcuPluralRewriter

/**
 * Which plural forms to ask a translation engine for, and from which source text.
 *
 * A plural key does not have the same forms from one language to the next: `en` has `one` /
 * `other`, `ru` `one` / `few` / `many` / `other`, `ja` only `other`. Translating form by form under
 * the same name leaves the Russian `few` and `many` without a source, and writes a Japanese `one`
 * that does not exist. The plan follows the target's categories ([PluralCategories]) instead, and
 * takes each one's text from the source form of the same category, or from `other` when the source
 * has none — a form the engine cannot know it must agree with "a few" or "many", so the caller
 * shows it for review.
 *
 * An ordinal group (`place_ordinal_one`…, read by `t('place', { count, ordinal: true })`) follows
 * the target's ordinal categories instead ([PluralCategories.ordinalOf]): `en` has `one` / `two` /
 * `few` / `other`, `ru` only `other`.
 *
 * Pure: no engine, no PSI. The caller reads the forms out of the translation file ([formsOf]) and
 * writes the translations back.
 */
object PluralTranslationPlan {

    /**
     * One form to translate: the target [category], the [source] text to send, and whether that
     * text is the source's `other` standing in for a category it lacks ([needsReview]).
     */
    data class Form(val category: String, val source: String, val needsReview: Boolean)

    sealed interface Plan {
        /** The forms to translate, in CLDR order; empty when the target has them all. */
        data class Forms(val forms: List<Form>) : Plan

        /**
         * A form of the group holds an ICU plural (`{count, plural, one {…} other {…}}`): an i18next
         * plural group and an ICU plural in one key do not combine. Not proposed. A key holding an ICU
         * plural on its own is translated through [icu].
         */
        data object IcuPlural : Plan
    }

    /**
     * The forms [targetLocale] needs that [existing] does not fill yet, each with its source text
     * from [sourceForms] (category to text). A form without any source text — no form of its
     * category, no `other` — is left out. An [ordinal] group takes the target's ordinal categories,
     * where [largeNumberForms] plays no part: an ordinal is always an integer.
     */
    fun of(
        sourceForms: Map<String, String>,
        targetLocale: String,
        existing: Map<String, String> = emptyMap(),
        largeNumberForms: Boolean = false,
        ordinal: Boolean = false,
    ): Plan {
        if (sourceForms.values.any(::isIcuPlural)) return Plan.IcuPlural
        val needed = if (ordinal) PluralCategories.ordinalOf(targetLocale) else PluralCategories.of(targetLocale, largeNumberForms)
        val other = sourceForms[PluralCategories.OTHER]?.takeIf { it.isNotBlank() }
        val forms = PluralCategories.ALL
            .filter { it in needed && existing[it].isNullOrBlank() }
            .mapNotNull { category ->
                val own = sourceForms[category]?.takeIf { it.isNotBlank() }
                when {
                    own != null -> Form(category, own, needsReview = false)
                    other != null -> Form(category, other, needsReview = true)
                    else -> null
                }
            }
        return Plan.Forms(forms)
    }

    /**
     * Whether the group whose `other` form is [otherKey] is an ordinal one: `place_ordinal_other`,
     * namespace and path included or not. A nested group (`steps.other`) has no ordinal spelling.
     */
    fun isOrdinalGroup(otherKey: String): Boolean = PluralKey.groupBase(otherKey)?.let(PluralKey::isOrdinalGroup) == true

    /**
     * The plural forms among [entries], by category: an i18next suffix (`item_one`, `item_other`)
     * or a nested group's own keys (`one`, `other`). Entries naming no category are left out.
     */
    fun formsOf(entries: Map<String, String>, pluralSeparator: String = "_"): Map<String, String> =
        entries.mapNotNull { (key, text) ->
            val category = key.substringAfterLast(pluralSeparator, key)
            if (category in PluralCategories.ALL) category to text else null
        }.toMap()

    /**
     * An ICU plural message ready to send for a target language: [source], its plural blocks rebuilt
     * with the target's categories, and the categories [copied] from `other` — to review once
     * translated, the engine not knowing it must agree with "a few" or "many".
     */
    data class IcuMessage(val source: String, val copied: Set<String>) {
        val needsReview: Boolean get() = copied.isNotEmpty()
    }

    /**
     * [text], an ICU plural, as it is to be translated into [targetLocale] ([IcuPluralRewriter]); null
     * when it cannot be rebuilt — nested more than a level deep, a plural without `other`, or braces
     * that do not balance.
     */
    fun icu(text: String, targetLocale: String, largeNumberForms: Boolean = false): IcuMessage? =
        (IcuPluralRewriter.forLocale(text, targetLocale, largeNumberForms) as? IcuPluralRewriter.Result.Rewritten)
            ?.let { IcuMessage(it.text, it.copied) }

    /** Whether [text] is, or holds, an ICU plural or selectordinal block. */
    fun isIcuPlural(text: String): Boolean = ICU_PLURAL.containsMatchIn(text)

    private val ICU_PLURAL = Regex("""\{\s*\w+\s*,\s*(plural|selectordinal)\s*,""")
}
