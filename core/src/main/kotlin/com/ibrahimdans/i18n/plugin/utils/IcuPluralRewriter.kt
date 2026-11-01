package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.tree.PluralCategories

/**
 * Rebuilds the `plural` blocks of an ICU message with the categories another language needs, so that
 * it can be machine-translated into that language.
 *
 * `{count, plural, one {# file} other {# files}}` has no `few` nor `many`, which Russian needs, and a
 * `one` Japanese never selects. Sent as it is, the translation keeps the English branches. Rewritten
 * for Russian it reads `{count, plural, one {# file} few {# files} many {# files} other {# files}}`:
 * each branch the source lacks is a copy of its `other` ([Result.Rewritten.copied]), to be reviewed
 * once translated, and a branch the target never selects is dropped. Exact branches (`=0`, `=1`)
 * and an `offset:` are kept as they are.
 *
 * `select` blocks are kept branch for branch, as their keys name no number. `selectordinal` blocks
 * are kept as they are too: [PluralCategories] lists the cardinal categories, not the ordinal ones.
 * A block inside a branch of another one (a count inside a gender) is rewritten; one more level is
 * [Result.Unsupported], as is a plural without `other` or a message whose braces do not balance.
 *
 * Only the syntax is rebuilt — the branch texts are copied as written, quotes included — and
 * everything outside a block is kept verbatim. Pure: no engine, no platform.
 */
object IcuPluralRewriter {

    sealed interface Result {
        /** The message rewritten as [text]; [copied]: the categories whose branch is a copy of `other`. */
        data class Rewritten(val text: String, val copied: Set<String>) : Result

        /** Nested too deeply, a plural without `other`, or braces that do not balance: left to the user. */
        data object Unsupported : Result
    }

    /** A block, and a block inside one of its branches: the gender-then-count case. */
    private const val MAX_DEPTH = 2

    private const val PLURAL = "plural"
    private const val SELECT = "select"
    private const val SELECTORDINAL = "selectordinal"

    /** [message] with each of its plural blocks rebuilt with the categories [locale] needs. */
    fun forLocale(message: String, locale: String, largeNumberForms: Boolean = false): Result {
        val needed = PluralCategories.of(locale, largeNumberForms)
        val copied = mutableSetOf<String>()
        val text = Scanner { block, depth -> write(block, depth, needed, copied) }.rewrite(message, 0, message.length, 0)
        return if (text == null) Result.Unsupported else Result.Rewritten(text, copied)
    }

    /**
     * What keeps [message] from being one written for [locale]: each plural block whose categories are
     * not those the language needs (`'count' needs one, few, many, other; has one, other`). Empty when
     * none; null when the message does not parse.
     */
    fun mismatches(message: String, locale: String, largeNumberForms: Boolean = false): List<String>? {
        val needed = PluralCategories.ALL.filter { it in PluralCategories.of(locale, largeNumberForms) }
        val found = mutableListOf<String>()
        val parsed = Scanner { block, depth ->
            if (block.type == PLURAL) {
                val has = block.branches.map { it.selector.lowercase() }.filter { it in PluralCategories.ALL }
                val ordered = PluralCategories.ALL.filter { it in has }
                if (ordered != needed || has.size != ordered.size) {
                    found += "'${block.argument}' needs ${needed.joinToString(", ")}; has ${has.joinToString(", ")}"
                }
            }
            write(block, depth, null, null)
        }.rewrite(message, 0, message.length, 0)
        return if (parsed == null) null else found
    }

    /** One `selector {body}` of a block, its body as written. */
    private data class Branch(val selector: String, val body: String)

    /**
     * `{argument, type, branches}`: [header] as written up to the second comma, [prefix] the tokens
     * carrying no body (`offset:1`).
     */
    private data class Block(
        val header: String, val argument: String, val type: String, val prefix: List<String>, val branches: List<Branch>,
    )

    /**
     * Walks a message, handing each branched block (`plural`, `select`, `selectordinal`) to [onBlock]
     * with its depth, and writing back what it returns; null as soon as anything does not parse.
     */
    private class Scanner(private val onBlock: Scanner.(Block, Int) -> String?) {

        /** `text[from, to)` with its blocks rewritten; null when its braces do not balance. */
        fun rewrite(text: String, from: Int, to: Int, depth: Int): String? {
            val out = StringBuilder(to - from)
            var i = from
            while (i < to) {
                when (text[i]) {
                    '\'' -> {
                        val end = IcuMessageRenderer.readQuoted(text, i, to, null)
                        out.append(text, i, end)
                        i = end
                    }
                    '}' -> return null
                    '{' -> {
                        val close = IcuMessageRenderer.matchingBrace(text, i, to) ?: return null
                        val block = blockOf(text, i, close)
                        if (block == null) {
                            // A variable (`{name}`) or a formatted value (`{n, number}`): kept as written.
                            out.append(text, i, close + 1)
                        } else {
                            if (depth >= MAX_DEPTH) return null
                            out.append(onBlock(block, depth) ?: return null)
                        }
                        i = close + 1
                    }
                    else -> {
                        out.append(text[i])
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** The branched block from [open] to [close], or null when it is no such block. */
        private fun blockOf(text: String, open: Int, close: Int): Block? {
            val first = text.indexOf(',', open).takeIf { it in open until close } ?: return null
            val argument = text.substring(open + 1, first).trim()
            if (argument.isEmpty() || argument.any { it == '{' || it == '}' }) return null
            val second = text.indexOf(',', first + 1).takeIf { it in open until close } ?: return null
            val type = text.substring(first + 1, second).trim().lowercase()
            if (type != PLURAL && type != SELECT && type != SELECTORDINAL) return null
            val prefix = mutableListOf<String>()
            val branches = mutableListOf<Branch>()
            var i = second + 1
            while (i < close) {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val start = i
                while (i < close && !text[i].isWhitespace() && text[i] != '{') i++
                val selector = text.substring(start, i)
                var j = i
                while (j < close && text[j].isWhitespace()) j++
                if (j >= close || text[j] != '{') {
                    // A token with no body, such as `offset:1`.
                    if (selector.isNotEmpty()) prefix += selector
                    continue
                }
                if (selector.isEmpty()) return null
                val end = IcuMessageRenderer.matchingBrace(text, j, close) ?: return null
                branches += Branch(selector, text.substring(j + 1, end))
                i = end + 1
            }
            return Block(text.substring(open, second + 1), argument, type, prefix, branches)
        }

        /**
         * [block] written back, each branch body rewritten one level deeper. With [needed], a plural
         * block takes those categories, a missing one copied from `other` and added to [copied]. Null
         * when it cannot be: a plural without `other`, or a body that does not parse.
         */
        fun write(block: Block, depth: Int, needed: Set<String>?, copied: MutableSet<String>?): String? {
            val branches = if (needed != null && block.type == PLURAL) {
                val exact = block.branches.filterNot { it.selector.lowercase() in PluralCategories.ALL }
                val byCategory = block.branches.filter { it.selector.lowercase() in PluralCategories.ALL }
                    .associateBy { it.selector.lowercase() }
                val other = byCategory[PluralCategories.OTHER] ?: return null
                exact + PluralCategories.ALL.filter { it in needed }.map { category ->
                    byCategory[category] ?: Branch(category, other.body).also { copied?.add(category) }
                }
            } else {
                block.branches
            }
            val parts = block.prefix + branches.map { branch ->
                val body = rewrite(branch.body, 0, branch.body.length, depth + 1) ?: return null
                "${branch.selector} {$body}"
            }
            return block.header + " " + parts.joinToString(" ") + "}"
        }
    }
}
