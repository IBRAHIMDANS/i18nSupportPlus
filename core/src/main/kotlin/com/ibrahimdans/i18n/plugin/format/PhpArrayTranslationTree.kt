package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * A Laravel translation file (`lang/en/messages.php`) read without the PSI: the array the file
 * returns, `return ['welcome' => 'Welcome', 'auth' => ['failed' => '…']];` or `return array(…);`,
 * nested arrays being the nested keys — the keys the plugin's `PhpArrayTree` reads.
 *
 * As there, only string keys are keys: an entry with an integer key or none is left out.
 *
 * Only literals are read: a string, a number, `true` / `false` / `null`, a nested array. A value
 * built by code — a concatenation, a call, a constant, a variable in a double-quoted string — would
 * hide the keys or the text only running PHP gives, so [read] answers [Reading.Partial] rather than
 * a tree with holes.
 */
class PhpArrayTranslationTree private constructor(node: ParsedNode, children: List<PhpArrayTranslationTree>?) :
    ParsedNodeTree(node, children) {

    /** What [read] finds in a PHP file. */
    sealed interface Reading {
        /** The returned array, read whole. */
        class Catalogue(val tree: PhpArrayTranslationTree) : Reading

        /** The returned array holds [construct], at [line] (1-based), which only running PHP would resolve. */
        data class Partial(val construct: String, val line: Int) : Reading

        /** A file of code: it does not start by returning an array. */
        data object NoArray : Reading
    }

    companion object {

        fun read(text: String): Reading {
            val tokens = try {
                Lexer(text).tokens()
            } catch (e: NotLiteral) {
                return Reading.Partial(e.construct, lineAt(text, e.offset))
            }
            val parser = Parser(tokens)
            if (!parser.skipToReturn()) return Reading.NoArray
            return try {
                Reading.Catalogue(parser.returnedArray())
            } catch (e: NotLiteral) {
                Reading.Partial(e.construct, lineAt(text, e.offset))
            }
        }

        /** The tree of the array [text] returns; null for a file of code or an array [read] finds [Reading.Partial]. */
        fun parse(text: String): PhpArrayTranslationTree? = (read(text) as? Reading.Catalogue)?.tree

        private fun lineAt(text: String, offset: Int): Int = 1 + (0 until offset.coerceAtMost(text.length)).count { text[it] == '\n' }
    }

    private enum class Kind { STRING, NUMBER, WORD, VARIABLE, SYMBOL }

    private class Token(val kind: Kind, val text: String, val span: TextSpan)

    private class NotLiteral(val construct: String, val offset: Int) : RuntimeException(null, null, false, false)

    /** PHP's tokens, as far as a translation file needs them: comments and the open tag dropped, strings decoded. */
    private class Lexer(private val text: String) {
        private var i = 0
        private val result = mutableListOf<Token>()

        fun tokens(): List<Token> {
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    text.startsWith("<?php", i) -> i += 5
                    text.startsWith("?>", i) -> i = text.length
                    text.startsWith("//", i) || (c == '#' && !text.startsWith("#[", i)) -> skipLine()
                    text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    text.startsWith("<<<", i) -> throw NotLiteral("heredoc", i)
                    c == '\'' -> single()
                    c == '"' -> double()
                    c.isDigit() -> word(Kind.NUMBER)
                    c == '$' -> word(Kind.VARIABLE, skip = 1)
                    c.isLetter() || c == '_' || c == '\\' -> word(Kind.WORD)
                    text.startsWith("=>", i) || text.startsWith("::", i) -> symbol(2)
                    text.startsWith("...", i) -> symbol(3)
                    else -> symbol(1)
                }
            }
            return result
        }

        private fun skipLine() {
            while (i < text.length && text[i] != '\n') i++
        }

        private fun symbol(length: Int) {
            result += Token(Kind.SYMBOL, text.substring(i, i + length), TextSpan(i, i + length))
            i += length
        }

        private fun word(kind: Kind, skip: Int = 0) {
            val start = i
            i += skip
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '\\' || (kind == Kind.NUMBER && text[i] == '.'))) i++
            result += Token(kind, text.substring(start, i), TextSpan(start, i))
        }

        /** `'…'`: only `\'` and `\\` are escapes. */
        private fun single() {
            val start = i++
            val value = StringBuilder()
            while (i < text.length && text[i] != '\'') {
                if (text[i] == '\\' && i + 1 < text.length && (text[i + 1] == '\'' || text[i + 1] == '\\')) i++
                value.append(text[i++])
            }
            i++
            result += Token(Kind.STRING, value.toString(), TextSpan(start, i))
        }

        /** `"…"`: the usual escapes; a `$name` or `{$` in it is code, not text. */
        private fun double() {
            val start = i++
            val value = StringBuilder()
            while (i < text.length && text[i] != '"') {
                val c = text[i]
                if (c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_' || text[i + 1] == '{')) {
                    throw NotLiteral("interpolated string", start)
                }
                if (c == '{' && i + 1 < text.length && text[i + 1] == '$') throw NotLiteral("interpolated string", start)
                if (c == '\\' && i + 1 < text.length) {
                    value.append(
                        when (val escaped = text[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"', '\\', '$' -> escaped
                            else -> "\\$escaped"
                        },
                    )
                    i += 2
                } else {
                    value.append(c)
                    i++
                }
            }
            i++
            result += Token(Kind.STRING, value.toString(), TextSpan(start, i))
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        private fun peek(ahead: Int = 0): Token? = tokens.getOrNull(position + ahead)

        private fun next(): Token? = tokens.getOrNull(position++)

        private fun isSymbol(token: Token?, symbol: String) = token?.kind == Kind.SYMBOL && token.text == symbol

        /**
         * Past the statements a translation file may open with — `declare(strict_types=1);`,
         * `namespace …;`, `use …;` — onto its `return`: false when another statement comes first.
         */
        fun skipToReturn(): Boolean {
            while (true) {
                val token = peek() ?: return false
                if (token.kind != Kind.WORD) return false
                when (token.text.lowercase()) {
                    "return" -> {
                        position++
                        return true
                    }
                    "declare", "namespace", "use" -> {
                        while (peek() != null && !isSymbol(peek(), ";")) position++
                        position++
                    }
                    else -> return false
                }
            }
        }

        /** The array after `return`; anything else is code. */
        fun returnedArray(): PhpArrayTranslationTree {
            val start = peek() ?: throw NotLiteral("missing array", tokens.lastOrNull()?.span?.end ?: 0)
            if (!startsArray()) throw NotLiteral(constructOf(start), start.span.start)
            val tree = array(key = null, keySpan = null)
            val after = peek()
            if (after != null && !isSymbol(after, ";")) throw NotLiteral(constructOf(after), after.span.start)
            return tree
        }

        private fun startsArray(): Boolean =
            isSymbol(peek(), "[") || (peek()?.kind == Kind.WORD && peek()!!.text.equals("array", ignoreCase = true) && isSymbol(peek(1), "("))

        private fun array(key: String?, keySpan: TextSpan?): PhpArrayTranslationTree {
            val open = next()!!
            val close = if (open.text == "[") "]" else ")".also { next() }
            val start = open.span.start
            val children = mutableListOf<PhpArrayTranslationTree>()
            while (true) {
                val token = peek() ?: throw NotLiteral("unclosed array", start)
                if (isSymbol(token, close)) break
                if (isSymbol(token, "...")) throw NotLiteral("spread", token.span.start)
                // `key => value`, or a value alone.
                val entryKey = if (isSymbol(peek(1), "=>")) {
                    val keyToken = next()!!
                    position++
                    when (keyToken.kind) {
                        Kind.STRING, Kind.NUMBER -> keyToken
                        else -> throw NotLiteral(constructOf(keyToken), keyToken.span.start)
                    }
                } else null
                val child = value(entryKey?.takeIf { it.kind == Kind.STRING })
                // Only string keys are translations, as in the plugin.
                if (entryKey?.kind == Kind.STRING) children += child
                val after = peek() ?: throw NotLiteral("unclosed array", start)
                when {
                    isSymbol(after, ",") -> position++
                    isSymbol(after, close) -> {}
                    else -> throw NotLiteral(constructOf(after), after.span.start)
                }
            }
            val end = next()!!.span.end
            return PhpArrayTranslationTree(ParsedNode(key, null, keySpan, TextSpan(start, end)), children)
        }

        private fun value(keyToken: Token?): PhpArrayTranslationTree {
            val key = keyToken?.text
            val keySpan = keyToken?.span
            if (startsArray()) return array(key, keySpan)
            val token = next() ?: throw NotLiteral("missing value", tokens.last().span.end)
            val literal = token.kind == Kind.STRING || token.kind == Kind.NUMBER ||
                (token.kind == Kind.WORD && token.text.lowercase() in setOf("true", "false", "null"))
            if (!literal) throw NotLiteral(constructOf(token), token.span.start)
            val following = peek()
            if (isSymbol(following, ".")) throw NotLiteral("concatenation", following!!.span.start)
            return PhpArrayTranslationTree(ParsedNode(key, token.text, keySpan, token.span), null)
        }

        /** What a report calls [token] when it is not a literal. */
        private fun constructOf(token: Token): String = when {
            token.kind == Kind.VARIABLE -> "variable"
            token.kind == Kind.WORD && isSymbol(peek(tokens.indexOf(token) - position + 1), "(") -> "call"
            token.kind == Kind.WORD && isSymbol(peek(tokens.indexOf(token) - position + 1), "::") -> "constant"
            token.kind == Kind.WORD -> "constant"
            else -> "'${token.text}'"
        }
    }
}
