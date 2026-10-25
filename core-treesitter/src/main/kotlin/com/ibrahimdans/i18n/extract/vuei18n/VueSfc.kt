package com.ibrahimdans.i18n.extract.vuei18n

/**
 * A Vue single-file component as code the TSX grammar reads: the `<script>` blocks as written, the
 * template's expressions — `{{ … }}` and the values of `:prop`, `@event` and `v-*` attributes — and
 * nothing else. Every other character becomes a space, line breaks excepted, so the text keeps its
 * length: a key's offset and line are those of the `.vue` file.
 *
 * Each expression is opened by a `;`, written over the `{{` or the quote before it, so that it reads
 * as a statement of its own. vue-i18n's markup holding keys outside any call is rewritten into a call
 * of [MARKUP_CALL], over the same characters:
 *  - `v-t="'home.title'"`, `v-t="{ path: 'home.title' }"` → `;$vt( 'home.title')`;
 *  - `<i18n-t keypath="terms.accept">` → `;$vt(   "terms.accept")`, and `:keypath="'…'"` alike.
 *
 * `<i18n>` blocks, `<style>`, comments and the template's text are left out.
 */
object VueSfc {

    /** The function the rewritten `v-t` and `keypath` call: no vue-i18n API is named so. */
    const val MARKUP_CALL = "\$vt"

    private val SCRIPT = Regex("""<script\b[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val INTERPOLATION = Regex("""\{\{(.*?)}}""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private const val OPEN_CALL = ";$MARKUP_CALL("
    private val KEYPATH_TAGS = setOf("i18n-t", "I18nT")

    /** The code of the component [text], as long as [text]. */
    fun codeOf(text: String): String {
        val code = CharArray(text.length) { if (text[it] == '\n' || text[it] == '\r') text[it] else ' ' }
        fun keep(range: IntRange) = range.forEach { code[it] = text[it] }

        for (script in SCRIPT.findAll(text)) keep(script.groups[1]!!.range)

        val start = text.indexOf("<template")
        val end = text.lastIndexOf("</template>")
        if (start < 0 || end < start) return String(code)
        val comments = COMMENT.findAll(text, start).map { it.range }.takeWhile { it.first < end }.toList()
        fun commented(offset: Int) = comments.any { offset in it }

        for (interpolation in INTERPOLATION.findAll(text.substring(0, end), start)) {
            if (commented(interpolation.range.first)) continue
            code[interpolation.range.first] = ';'
            keep(interpolation.groups[1]!!.range)
        }
        var offset = start
        while (offset < end) {
            val comment = comments.firstOrNull { offset in it }
            offset = when {
                comment != null -> comment.last + 1
                text[offset] == '<' && offset + 1 < end && text[offset + 1].isLetter() -> tag(text, offset, end, code)
                else -> offset + 1
            }
        }
        return String(code)
    }

    /** Writes the expressions of the tag opened at [open] into [code]; the offset past it. */
    private fun tag(text: String, open: Int, end: Int, code: CharArray): Int {
        var i = open + 1
        while (i < end && (text[i].isLetterOrDigit() || text[i] in "-_.:")) i++
        val tagName = text.substring(open + 1, i)
        while (i < end) {
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end || text[i] == '>') return i + 1
            if (text[i] == '/') { i++; continue }
            val nameStart = i
            while (i < end && !text[i].isWhitespace() && text[i] !in "=>/") i++
            val name = text.substring(nameStart, i)
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end || text[i] != '=') continue
            i++
            while (i < end && text[i].isWhitespace()) i++
            val quote = text.getOrNull(i)?.takeIf { it == '"' || it == '\'' } ?: continue
            val close = text.indexOf(quote, i + 1).takeIf { it in 0 until end } ?: return end
            attribute(text, tagName, name, nameStart, i, close, code)
            i = close + 1
        }
        return end
    }

    /** Writes the attribute [name], its value quoted from [open] to [close], into [code] when it holds code or a key. */
    private fun attribute(text: String, tagName: String, name: String, nameStart: Int, open: Int, close: Int, code: CharArray) {
        val value = open + 1 until close
        val keypath = tagName in KEYPATH_TAGS
        when {
            name == "v-t" || keypath && (name == ":keypath" || name == "v-bind:keypath") -> {
                // The value is an expression: the call opens over the name, the closing quote closes it.
                OPEN_CALL.forEachIndexed { index, c -> code[nameStart + index] = c }
                value.forEach { code[it] = text[it] }
                code[close] = ')'
            }
            keypath && name == "keypath" -> {
                // The value is the key itself: kept quoted, the call closed just past the quote.
                if (text.getOrNull(close + 1)?.let { it.isWhitespace() || it == '>' || it == '/' } != true) return
                OPEN_CALL.forEachIndexed { index, c -> code[nameStart + index] = c }
                (open..close).forEach { code[it] = text[it] }
                code[close + 1] = ')'
            }
            name.startsWith(":") || name.startsWith("@") || name.startsWith("v-") -> {
                code[open] = ';'
                value.forEach { code[it] = text[it] }
            }
        }
    }
}
