package com.ibrahimdans.i18n.plugin.check

import com.ibrahimdans.i18n.plugin.translate.Glossary

/**
 * A term of the glossary that the reference's value holds and a translation does not render as the
 * glossary says: [expected] is the term itself when it is kept as written (a product name), else the
 * term's translation for the locale.
 */
data class GlossaryBreach(val term: String, val expected: String, val kept: Boolean)

/**
 * The glossary applied to a translation already written, by hand or by an agent: the engines follow it
 * when they translate, nothing checked what was written otherwise.
 *
 * Terms are matched as whole words, ignoring case — `Cart` at the start of a sentence is still the
 * term `cart`. A term the reference does not hold, or one with translations but none for the locale,
 * asks nothing of the value. Inflected forms are not recognised: `paniers` does not satisfy `panier`.
 */
object GlossaryCheck {

    /** The terms of [glossary] that [value], translated into [locale] from [reference], does not respect. */
    fun breaches(glossary: Glossary, reference: String, value: String, locale: String): List<GlossaryBreach> =
        glossary.terms.asSequence()
            .filter { it.term.isNotBlank() && contains(reference, it.term) }
            .mapNotNull { term ->
                val expected = if (term.kept) term.term else term.translationFor(locale) ?: return@mapNotNull null
                GlossaryBreach(term.term, expected, term.kept).takeUnless { contains(value, expected) }
            }
            .distinctBy { it.term.lowercase() }
            .toList()

    /** Whether [text] holds [word] as a whole word, ignoring case. */
    internal fun contains(text: String, word: String): Boolean {
        val needle = word.trim()
        if (needle.isEmpty()) return false
        val before = if (needle.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}_])" else ""
        val after = if (needle.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}_])" else ""
        return Regex(before + Regex.escape(needle) + after, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
