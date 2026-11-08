package com.ibrahimdans.i18n.plugin.translate

/**
 * The project's terms, so an engine neither translates a product name nor varies a business term.
 *
 * - A term without translations is **kept as it is**, for every engine: masked like a variable before
 *   the text leaves ([protect]) and put back as written. Matched case-sensitively, as a whole word.
 * - A term with translations, by target locale, is given to the engines whose request holds
 *   `{glossary}` — an LLM prompt ([prompt]), and to DeepL as a glossary of its own ([DeeplGlossary]). Google and LibreTranslate do not read it.
 */
data class Glossary(val terms: List<Term> = emptyList()) {

    /** [term] and its [translations] by locale (`fr`, `pt_BR`); none means "never translate it". */
    data class Term(val term: String, val translations: Map<String, String> = emptyMap()) {
        val kept: Boolean get() = translations.isEmpty()

        /**
         * The translation for [target], which may come rewritten for its engine (`FR`, `EN-US`):
         * the locale written alike, else the bare language, else another variant of it.
         */
        fun translationFor(target: String): String? = valueForLocale(translations.filterValues { it.isNotBlank() }, target)
    }

    /** One pattern for every kept term, the longest first, or null when none is kept. */
    private val keptPattern: Regex? by lazy {
        terms.filter { it.kept && it.term.isNotBlank() }.map { it.term }.distinct().sortedByDescending { it.length }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|") { term ->
                val before = if (term.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}_])" else ""
                val after = if (term.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}_])" else ""
                before + Regex.escape(term) + after
            }
            ?.let(::Regex)
    }

    /**
     * [masked] with each kept term hidden behind one more `<x id="N"/>` tag, a variable the
     * translation may move. The tags already there are left alone.
     */
    fun protect(masked: PlaceholderMask.Masked): PlaceholderMask.Masked {
        val pattern = keptPattern ?: return masked
        val tokens = masked.tokens.toMutableList()
        val out = StringBuilder()
        fun gap(text: String) {
            out.append(pattern.replace(text) { match ->
                "<x id=\"${tokens.size}\"/>".also { tokens += PlaceholderMask.Token(PlaceholderMask.Kind.VARIABLE, match.value) }
            })
        }
        var last = 0
        for (tag in MASK_TAG.findAll(masked.text)) {
            gap(masked.text.substring(last, tag.range.first))
            out.append(tag.value)
            last = tag.range.last + 1
        }
        gap(masked.text.substring(last))
        return if (tokens.size == masked.tokens.size) masked else PlaceholderMask.Masked(out.toString(), tokens)
    }

    /**
     * What `{glossary}` becomes for [target]: a sentence opening with a space, to follow the
     * prompt's last one — empty when no term has a translation there, leaving the request as it was.
     */
    fun prompt(target: String): String {
        val pairs = terms.filter { !it.kept && it.term.isNotBlank() }.mapNotNull { t -> t.translationFor(target)?.let { t.term to it } }
        if (pairs.isEmpty()) return ""
        return " Use this glossary, each term with its translation: " +
            pairs.joinToString("; ") { (term, translation) -> "\"$term\" → \"$translation\"" } + "."
    }

    companion object {
        val EMPTY = Glossary()

        private val MASK_TAG = Regex("""<x id="\d+"/>""")
    }
}
