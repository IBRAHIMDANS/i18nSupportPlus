package com.ibrahimdans.i18n.extract.svelte

/**
 * A Svelte component as code the TSX grammar reads: the `<script>` blocks as written and the
 * markup's expressions — the `{…}` of text and attributes, and the expression of a block tag
 * (`{#if …}`, `{:else if …}`, `{#each … as item}`, `{#key …}`, `{#await …}`, `{@html …}`,
 * `{@render …}`, `{@const …}`) — and nothing else. Every other character becomes a space, line
 * breaks excepted, so the text keeps its length: a key's offset and line are those of the file.
 *
 * Each expression is opened by a `;`, written over its `{`, so that it reads as a statement of its
 * own. `<style>` blocks, comments, closing tags (`{/if}`), `{:else}`, `{:then …}`, `{:catch …}`,
 * `{@debug …}` and spread attributes are left out.
 */
object SvelteSfc {

    private val SCRIPT = Regex("""<script\b[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val STYLE = Regex("""<style\b[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** The block tags whose expression follows the keyword. */
    private val EXPRESSION_TAGS = listOf("#if", "#key", "#await", "#each", ":else if", "@html", "@render", "@const", "@attach")
    private const val EACH = "#each"
    private const val EACH_ALIAS = " as "

    /** The code of the component [text], as long as [text]. */
    fun codeOf(text: String): String {
        val code = CharArray(text.length) { if (text[it] == '\n' || text[it] == '\r') text[it] else ' ' }
        for (script in SCRIPT.findAll(text)) script.groups[1]!!.range.forEach { code[it] = text[it] }

        val skipped = (SCRIPT.findAll(text) + STYLE.findAll(text) + COMMENT.findAll(text)).map { it.range }.sortedBy { it.first }.toList()
        var offset = 0
        while (offset < text.length) {
            val block = skipped.firstOrNull { offset in it }
            offset = when {
                block != null -> block.last + 1
                text[offset] == '{' -> expression(text, offset, code)
                else -> offset + 1
            }
        }
        return String(code)
    }

    /** Writes the expression of the `{` at [open] into [code]; the offset past its `}`. */
    private fun expression(text: String, open: Int, code: CharArray): Int {
        val close = closingBrace(text, open) ?: return text.length
        val inner = text.substring(open + 1, close)
        val trimmed = inner.trimStart()
        val lead = open + 1 + (inner.length - trimmed.length)
        val range: IntRange? = when {
            trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.startsWith("...") -> null
            trimmed[0] in "#:@" -> {
                val tag = EXPRESSION_TAGS.firstOrNull { trimmed.startsWith(it) && trimmed.getOrNull(it.length)?.isWhitespace() == true }
                tag?.let {
                    // `{#each items as item}`: the alias is no expression of the template's.
                    val alias = if (it == EACH) inner.lastIndexOf(EACH_ALIAS) else -1
                    lead + it.length until if (alias >= 0) open + 1 + alias else close
                }
            }
            else -> lead until close
        }
        if (range != null) {
            code[open] = ';'
            range.forEach { code[it] = text[it] }
        }
        return close + 1
    }

    /** The `}` closing the `{` at [open], braces inside strings and nested objects counted; null when none does. */
    private fun closingBrace(text: String, open: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> when (c) {
                    '\\' -> i++
                    quote -> quote = null
                }
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }
}
