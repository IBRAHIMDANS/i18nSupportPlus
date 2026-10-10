package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel

/**
 * Hides what a translation engine must not touch — variables, markup tags, ICU syntax — behind
 * neutral `<x id="N"/>` tags, and puts it back afterwards.
 *
 * An engine translates or moves `{{name}}`, `%s` or `<0>`: the message then breaks at runtime. DeepL
 * keeps XML tags in place with `tag_handling=xml`, and an LLM copies them when asked to, so each
 * protected piece becomes one such tag, numbered in order.
 *
 * - **Variables** are those [DialogViewModel.variableRanges] recognises (`{{x}}`, `%{x}`, `{x}`,
 *   `%s`, `%1$s`), plus `#` inside a plural branch. A translation may move them.
 * - **Markup** (`<0>`, `</0>`, `<b>`, `<br/>`) is masked tag by tag, the text between them left to
 *   translate. The tags must still nest correctly once restored.
 * - **ICU blocks** (`{count, plural, one {# item} other {# items}}`) keep their branch text
 *   translatable: only the syntax is masked — `{count, plural,`, `one {`, each `}`. Those pieces
 *   must come back in their original order, or the block no longer parses.
 *
 * Pure: no platform, no network.
 */
object PlaceholderMask {

    /** What a protected piece is, which decides what the translation may do with it. */
    enum class Kind { VARIABLE, MARKUP, ICU }

    data class Token(val kind: Kind, val original: String)

    /** [text] with every protected piece replaced by `<x id="N"/>`, N indexing [tokens]. */
    data class Masked(val text: String, val tokens: List<Token>)

    /** Why a translation was refused. */
    enum class Rejection {
        /** A tag of the masked text is absent. */
        MISSING,

        /** A tag appears more than once. */
        DUPLICATED,

        /** A tag that was never sent appears. */
        UNKNOWN,

        /** The ICU syntax pieces came back in another order. */
        ICU_REORDERED,

        /** The markup tags no longer nest. */
        MARKUP_BROKEN
    }

    sealed interface Unmasked {
        data class Restored(val text: String) : Unmasked
        data class Rejected(val reason: Rejection) : Unmasked
    }

    private val ICU_HEADER = Regex("""^\{\s*[\w.]+\s*,\s*(plural|select|selectordinal)\s*,""")
    private val ICU_SELECTOR = Regex("""\s*(=\d+|[\w-]+|offset:\d+\s+[\w=]+)\s*\{""")
    private val MARKUP = Regex("""</?[A-Za-z0-9][\w.-]*(\s[^<>]*)?/?>""")
    private val TAG = Regex("""<x\s+id\s*=\s*["']?(\d+)["']?\s*/?>(\s*</x>)?""")
    private val MARKUP_NAME = Regex("""^</?([A-Za-z0-9][\w.-]*)""")

    fun mask(text: String): Masked {
        val tokens = mutableListOf<Token>()
        val out = StringBuilder()
        scan(text, 0, text.length, inPlural = false, tokens, out)
        return Masked(out.toString(), tokens)
    }

    /** Masks [text] from [start] to [end] into [out]; [inPlural] makes `#` a variable. */
    private fun scan(text: String, start: Int, end: Int, inPlural: Boolean, tokens: MutableList<Token>, out: StringBuilder) {
        // Ranges come relative to the slice: shifted back onto [text].
        val variables = DialogViewModel.variableRanges(text.substring(start, end)).associate { it.first + start to it.last + start }
        var i = start
        while (i < end) {
            val c = text[i]
            if (c == '{') {
                val close = matchingBrace(text, i, end)
                val header = if (close > 0) ICU_HEADER.find(text.substring(i, close + 1)) else null
                if (header != null) {
                    i = icu(text, i, close, header.value, header.groupValues[1] == "plural", tokens, out)
                    continue
                }
            }
            val variable = variables[i]
            val markup = if (c == '<') MARKUP.matchAt(text, i)?.takeIf { it.range.last < end } else null
            when {
                variable != null -> { protect(Kind.VARIABLE, text.substring(i, variable + 1), tokens, out); i = variable + 1 }
                markup != null -> { protect(Kind.MARKUP, markup.value, tokens, out); i = markup.range.last + 1 }
                c == '#' && inPlural -> { protect(Kind.VARIABLE, "#", tokens, out); i++ }
                else -> { out.append(c); i++ }
            }
        }
    }

    /** Masks the ICU block from [open] to [close], its branches scanned as text; returns the index after it. */
    private fun icu(text: String, open: Int, close: Int, header: String, plural: Boolean, tokens: MutableList<Token>, out: StringBuilder): Int {
        protect(Kind.ICU, header, tokens, out)
        var i = open + header.length
        while (i < close) {
            val selector = ICU_SELECTOR.matchAt(text, i)
            if (selector == null) {
                // Not a branch: the block is malformed, keep the rest of it whole.
                protect(Kind.ICU, text.substring(i, close), tokens, out)
                i = close
                break
            }
            protect(Kind.ICU, selector.value, tokens, out)
            val branchOpen = selector.range.last
            val branchClose = matchingBrace(text, branchOpen, close)
            if (branchClose < 0) {
                protect(Kind.ICU, text.substring(branchOpen + 1, close), tokens, out)
                i = close
                break
            }
            scan(text, branchOpen + 1, branchClose, plural, tokens, out)
            protect(Kind.ICU, "}", tokens, out)
            i = branchClose + 1
            // Whitespace after the last branch belongs to the closing piece.
            if (text.substring(i, close).isBlank()) {
                protect(Kind.ICU, text.substring(i, close + 1), tokens, out)
                return close + 1
            }
        }
        protect(Kind.ICU, "}", tokens, out)
        return close + 1
    }

    /** The index of the `}` closing the `{` at [open], before [end]; -1 when it does not close. */
    private fun matchingBrace(text: String, open: Int, end: Int): Int {
        var depth = 0
        for (j in open until end) {
            when (text[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j
            }
        }
        return -1
    }

    private fun protect(kind: Kind, original: String, tokens: MutableList<Token>, out: StringBuilder) {
        out.append("<x id=\"").append(tokens.size).append("\"/>")
        tokens.add(Token(kind, original))
    }

    /** [translated] with the tags of [masked] replaced by what they hid, or why it is refused. */
    fun unmask(translated: String, masked: Masked): Unmasked {
        val found = TAG.findAll(translated).toList()
        val ids = found.map { it.groupValues[1].toInt() }
        if (ids.any { it !in masked.tokens.indices }) return Unmasked.Rejected(Rejection.UNKNOWN)
        if (ids.size != ids.toSet().size) return Unmasked.Rejected(Rejection.DUPLICATED)
        if (ids.size != masked.tokens.size) return Unmasked.Rejected(Rejection.MISSING)
        val icu = ids.filter { masked.tokens[it].kind == Kind.ICU }
        if (icu != icu.sorted()) return Unmasked.Rejected(Rejection.ICU_REORDERED)
        val markup = ids.filter { masked.tokens[it].kind == Kind.MARKUP }.map { masked.tokens[it].original }
        if (!nests(markup)) return Unmasked.Rejected(Rejection.MARKUP_BROKEN)
        return Unmasked.Restored(TAG.replace(translated) { masked.tokens[it.groupValues[1].toInt()].original })
    }

    /** Whether the [tags], in order, open and close as a well-formed tree. */
    private fun nests(tags: List<String>): Boolean {
        val open = ArrayDeque<String>()
        for (tag in tags) {
            val name = MARKUP_NAME.find(tag)?.groupValues?.get(1) ?: return false
            when {
                tag.endsWith("/>") -> Unit
                tag.startsWith("</") -> if (open.removeLastOrNull() != name) return false
                else -> open.addLast(name)
            }
        }
        return open.isEmpty()
    }
}
