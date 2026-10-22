package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.tree.PluralCategories

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
         * The source holds an ICU plural (`{count, plural, one {…} other {…}}`): its branches depend
         * on the target language and must be rebuilt, which is not a text translation. Not proposed.
         */
        data object IcuPlural : Plan
    }

    /**
     * The forms [targetLocale] needs that [existing] does not fill yet, each with its source text
     * from [sourceForms] (category to text). A form without any source text — no form of its
     * category, no `other` — is left out.
     */
    fun of(
        sourceForms: Map<String, String>,
        targetLocale: String,
        existing: Map<String, String> = emptyMap(),
        largeNumberForms: Boolean = false,
    ): Plan {
        if (sourceForms.values.any(::isIcuPlural)) return Plan.IcuPlural
        val needed = PluralCategories.of(targetLocale, largeNumberForms)
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
     * The plural forms among [entries], by category: an i18next suffix (`item_one`, `item_other`)
     * or a nested group's own keys (`one`, `other`). Entries naming no category are left out.
     */
    fun formsOf(entries: Map<String, String>, pluralSeparator: String = "_"): Map<String, String> =
        entries.mapNotNull { (key, text) ->
            val category = key.substringAfterLast(pluralSeparator, key)
            if (category in PluralCategories.ALL) category to text else null
        }.toMap()

    /** Whether [text] is, or holds, an ICU plural or selectordinal block. */
    fun isIcuPlural(text: String): Boolean = ICU_PLURAL.containsMatchIn(text)

    private val ICU_PLURAL = Regex("""\{\s*\w+\s*,\s*(plural|selectordinal)\s*,""")
}
