package com.ibrahimdans.i18n.plugin.translate

/**
 * How the project wants its translations written, by the engines able to follow it.
 *
 * - [formality], by target locale (`de`, `pt_BR`): DeepL reads it as its own `formality` parameter
 *   (`{formality}`), an LLM as a sentence of its prompt (`{instruction}`). Google and LibreTranslate have no
 *   such option. A locale missing here gets the engine's default.
 * - [instruction], the project's own words, one instruction per line: for an LLM prompt only, before the
 *   instruction typed in the key's dialog. DeepL's `custom_instructions` is left out: it holds a few target
 *   languages only and switches to its slower model.
 */
data class Style(val formality: Map<String, Formality> = emptyMap(), val instruction: String = "") {

    /** The form of address for [target], which may come rewritten for its engine (`DE`, `EN-US`). */
    fun formalityFor(target: String): Formality = valueForLocale(formality, target) ?: Formality.DEFAULT

    companion object {
        val EMPTY = Style()
    }
}

/**
 * The form of address of a translation: `Sie` or `du`, `vous` or `tu`. [deepl] is DeepL's `formality`
 * value: the `prefer_` ones fall back to the default for a language without the distinction, where `more`
 * and `less` fail the request. [sentence] is what an LLM prompt gets, opening with a space — empty for the
 * default, leaving the request as it was.
 */
enum class Formality(val deepl: String, val sentence: String) {
    DEFAULT("default", ""),
    FORMAL("prefer_more", " Use the formal form of address (like \"Sie\" in German or \"vous\" in French)."),
    INFORMAL("prefer_less", " Use the informal form of address (like \"du\" in German or \"tu\" in French).")
}
