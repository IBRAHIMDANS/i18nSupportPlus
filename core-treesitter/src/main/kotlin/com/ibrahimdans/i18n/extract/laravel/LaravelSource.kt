package com.ibrahimdans.i18n.extract.laravel

/**
 * A PHP file or a Blade view as code the TSX grammar reads: each of Laravel's translation calls
 * whose key is a string literal, rewritten over the same characters into `__('key');`, and nothing
 * else. Every other character becomes a space, line breaks excepted, so the text keeps its length:
 * a key's offset and line are those of the file.
 *
 * The calls are those the plugin reads in a Laravel project: the helpers `__()`, `trans()` and
 * `trans_choice()`, the translator's `get` and `choice` on the `Lang` facade or `app('translator')`,
 * and Blade's `@lang()` and `@choice()`. `$request->get('page')` is none: the object called is part
 * of the match. Left out: a key built at runtime (a double-quoted string interpolating a variable),
 * a package's key (`courier::messages.sent`) unless the project overrides that package's file, and
 * calls in a comment — Blade's `{{-- --}}`, PHP's `/* */`, and `//` or `#` opening a line.
 *
 * A package's key is kept for the scopes the project holds an override of, `courier::messages` for
 * `lang/vendor/courier/en/messages.php`: as in the plugin, a package without one ships its translations
 * under Composer's `vendor/`, and its keys are neither read nor reported.
 */
object LaravelSource {

    /** The function every call is rewritten to: [LaravelKeyExtractor] reads it. */
    const val CALL = "__"

    private val CALL_HEAD = Regex(
        listOf(
            """(?<![\w$>:\\])\\?(?:__|trans_choice|trans)""",
            """(?<![\w$>\\])\\?(?:Illuminate\\Support\\Facades\\)?Lang::(?:get|choice)""",
            """app\(\s*['"]translator['"]\s*\)\s*->\s*(?:get|choice)""",
            """(?<![\w@])@(?:lang|choice)""",
        ).joinToString("|", "(?:", """)\s*\(\s*(?=['"])"""),
    )
    private val COMMENT = Regex("""\{\{--.*?--}}|/\*.*?\*/|(?m)^[ \t]*(?://|#(?!\[))[^\n]*""", RegexOption.DOT_MATCHES_ALL)
    private const val PACKAGE_SEPARATOR = "::"

    /** Laravel's separator between a file and its key: `courier::messages.sent`. */
    private const val KEY_SEPARATOR = "."

    /** The code of [text], as long as [text]; a package's key is kept for one of [packageScopes] only. */
    fun codeOf(text: String, packageScopes: Set<String> = emptySet()): String {
        val code = CharArray(text.length) { if (text[it] == '\n' || text[it] == '\r') text[it] else ' ' }
        val comments = COMMENT.findAll(text).map { it.range }.toList()
        for (head in CALL_HEAD.findAll(text)) {
            if (comments.any { head.range.first in it }) continue
            val open = head.range.last + 1
            val close = closingQuote(text, open) ?: continue
            val literal = text.substring(open + 1, close)
            if (text[open] == '"' && '$' in literal) continue
            if (PACKAGE_SEPARATOR in literal && packageScopes.none { literal.startsWith(it + KEY_SEPARATOR) }) continue
            // The head is at least as long as `__(`: the call opens over its last characters.
            "$CALL(".forEachIndexed { index, c -> code[open - CALL.length - 1 + index] = c }
            (open..close).forEach { code[it] = text[it] }
            if (close + 1 < text.length) code[close + 1] = ')'
            if (close + 2 < text.length) code[close + 2] = ';'
        }
        return String(code)
    }

    /** The quote closing the string literal opened at [open], escapes skipped; null when it ends its line unclosed. */
    private fun closingQuote(text: String, open: Int): Int? {
        val quote = text[open]
        var i = open + 1
        while (i < text.length && text[i] != '\n') {
            when (text[i]) {
                '\\' -> i++
                quote -> return i
            }
            i++
        }
        return null
    }
}
