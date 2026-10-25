package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey

/**
 * The reference key a translation is translated from, hence the source a review records and is
 * compared against ([ReviewSource], [shown]).
 *
 * The same key, when the reference locale holds it. A plural form does not always: `en` has
 * `item_one` / `item_other`, `ru` adds `item_few` and `item_many`. Read as "the same key", those two
 * had no source, so a change of the English `item_other` never turned their approval stale. Their
 * source is the reference's `other` form instead — the one [com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan]
 * translates a form the source lacks from, so that what a review records is what was translated.
 *
 * The forms are the ones [PluralKey.stripSuffix] recognises: CLDR categories (`item_few`, always
 * `_`) and the legacy numeric ones (`item-5`, the module's plural separator), whose `other` is the
 * reference's highest count — `5`, then `2` — and the nested ones (`steps.few`, an object of
 * categories), whose `other` is their sibling ([PluralKey.nestedOtherForm]).
 *
 * Every place reading or recording a source goes through here — the table, the key dialog, the
 * stale translations inspection, *Fill Missing Translations* — so they agree on what is stale.
 */
object ReviewSources {

    /** The legacy numeric counts standing for the general plural, the widest first. */
    private val NUMERIC_OTHERS = listOf("5", "2")

    /**
     * The text [key] is translated from: its own value in the reference, [referenceText] (key → its
     * value there, null when absent); else, for a plural form, the reference's `other` form. Null
     * when the reference holds neither, or only empty values: no source is known then.
     */
    fun sourceTextOf(key: String, pluralSeparator: String, keySeparator: String = ".", referenceText: (String) -> String?): String? {
        referenceText(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        return otherFormsOf(key, pluralSeparator, keySeparator).firstNotNullOfOrNull { form -> referenceText(form)?.takeIf { it.isNotEmpty() } }
    }

    /**
     * The keys standing for [key]'s `other` form, in order of preference — none when [key] is no
     * plural form, or is that form itself.
     */
    internal fun otherFormsOf(key: String, pluralSeparator: String, keySeparator: String = "."): List<String> {
        val base = PluralKey.stripSuffix(key, pluralSeparator)
        if (base == key) return listOfNotNull(PluralKey.nestedOtherForm(key, keySeparator))
        val cldr = key.removePrefix(base).removePrefix("_") in PluralCategories.ALL
        val candidates = if (cldr) listOf(PluralKey.defaultForm(base)) else NUMERIC_OTHERS.map { base + pluralSeparator + it }
        return candidates - key
    }
}
