package com.ibrahimdans.i18n.plugin.format

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import java.io.IOException

/**
 * A JSON translation file read with jackson-core, the same tree `JsonElementTree` gives over the
 * PSI: objects are the trees, anything else — string, number, boolean, null, array — a leaf.
 *
 * As there, the root object's `$schema` (a Paraglide message file's first property) is left out of
 * [entries] and [findChildren] but still found by [findChild]; a nested `$schema` is a key.
 */
class JsonTranslationTree private constructor(
    node: ParsedNode,
    children: List<JsonTranslationTree>?,
    private val isRoot: Boolean,
) : ParsedNodeTree(node, children) {

    override fun listed(): List<ParsedNodeTree> =
        if (isRoot) super.listed().filterNot { it.value().key == SCHEMA } else super.listed()

    companion object {
        private const val SCHEMA = "\$schema"

        private val factory = JsonFactory()

        /**
         * The tree of [text], whose root must be an object; null for any other root or for text
         * that is not JSON — where the PSI would still build a tree around the error.
         */
        fun parse(text: String): JsonTranslationTree? =
            try {
                factory.createParser(text).use { parser ->
                    if (parser.nextToken() != JsonToken.START_OBJECT) return null
                    val root = read(parser, text, key = null, keySpan = null, isRoot = true)
                    root.takeIf { parser.nextToken() == null }
                }
            } catch (e: IOException) {
                null
            }

        /** The value [parser] stands on, its closing token consumed. */
        private fun read(parser: JsonParser, text: String, key: String?, keySpan: TextSpan?, isRoot: Boolean = false): JsonTranslationTree {
            val start = parser.currentTokenLocation().charOffset.toInt()
            return when (parser.currentToken()) {
                JsonToken.START_OBJECT -> {
                    val children = mutableListOf<JsonTranslationTree>()
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        val name = parser.currentName()
                        val nameStart = parser.currentTokenLocation().charOffset.toInt()
                        parser.nextToken()
                        children += read(parser, text, name, TextSpan(nameStart, stringEnd(text, nameStart)))
                    }
                    JsonTranslationTree(ParsedNode(key, null, keySpan, TextSpan(start, end(parser))), children, isRoot)
                }
                // Read before taking the end: the parser only reaches the closing quote then.
                JsonToken.VALUE_STRING -> parser.text.let { value -> leaf(key, value, keySpan, start, end(parser)) }
                // An array or another scalar: a leaf whose value is its source text.
                else -> {
                    parser.skipChildren()
                    val end = end(parser)
                    leaf(key, text.substring(start, end), keySpan, start, end)
                }
            }
        }

        private fun leaf(key: String?, value: String, keySpan: TextSpan?, start: Int, end: Int): JsonTranslationTree =
            JsonTranslationTree(ParsedNode(key, value, keySpan, TextSpan(start, end)), null, isRoot = false)

        private fun end(parser: JsonParser): Int = parser.currentLocation().charOffset.toInt()

        /** The offset right after the closing quote of the string literal opening at [start]. */
        private fun stringEnd(text: String, start: Int): Int {
            var i = start + 1
            while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
            return i + 1
        }
    }
}
