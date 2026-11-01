package com.ibrahimdans.i18n.plugin.format

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import java.io.IOException

/**
 * A JSON translation file read with jackson-core, the same tree `JsonElementTree` gives over the
 * PSI: objects are the trees, anything else — string, number, boolean, null, array — a leaf.
 *
 * As there, the root object's `$schema` (a Paraglide message file's first property) is left out of
 * [entries] and [findChildren] but still found by [findChild]; a nested `$schema` is a key. In a
 * Flutter ARB catalogue ([arb]), the root's `@` properties — `@@locale`, the `@greeting` describing
 * `greeting`'s placeholders — are metadata the same way.
 *
 * A `.json5` file is read with the JSON5 leniencies Jackson knows — comments, unquoted keys, single
 * quotes, trailing commas, JSON5 numbers — and keeps exact spans. Only reading: [JsonTranslationEditor]
 * writes plain JSON and parses strictly.
 */
class JsonTranslationTree private constructor(
    node: ParsedNode,
    children: List<JsonTranslationTree>?,
    private val isRoot: Boolean,
    private val arb: Boolean = false,
) : ParsedNodeTree(node, children) {

    override fun listed(): List<ParsedNodeTree> =
        if (isRoot) super.listed().filterNot { isMetadata(it.value().key) } else super.listed()

    private fun isMetadata(key: String?): Boolean = key == SCHEMA || (arb && key?.startsWith(ARB_METADATA) == true)

    companion object {
        private const val SCHEMA = "\$schema"
        private const val ARB_METADATA = "@"

        private val factory = JsonFactory()

        private val json5Factory = JsonFactory.builder()
            .enable(
                JsonReadFeature.ALLOW_JAVA_COMMENTS,
                JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
                JsonReadFeature.ALLOW_SINGLE_QUOTES,
                JsonReadFeature.ALLOW_TRAILING_COMMA,
                JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,
                JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
                JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,
                JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER,
            )
            .build()

        /**
         * The tree of [text], whose root must be an object; null for any other root or for text
         * that is not JSON — or not JSON5 when [json5] — where the PSI would still build a tree
         * around the error. [arb] reads a Flutter catalogue, its root's `@` metadata left out of the keys.
         */
        fun parse(text: String, json5: Boolean = false, arb: Boolean = false): JsonTranslationTree? =
            try {
                (if (json5) json5Factory else factory).createParser(text).use { parser ->
                    if (parser.nextToken() != JsonToken.START_OBJECT) return null
                    val root = read(parser, text, key = null, keySpan = null, isRoot = true, arb = arb)
                    root.takeIf { parser.nextToken() == null }
                }
            } catch (e: IOException) {
                null
            }

        /** The value [parser] stands on, its closing token consumed. */
        private fun read(parser: JsonParser, text: String, key: String?, keySpan: TextSpan?, isRoot: Boolean = false, arb: Boolean = false): JsonTranslationTree {
            val start = parser.currentTokenLocation().charOffset.toInt()
            return when (parser.currentToken()) {
                JsonToken.START_OBJECT -> {
                    val children = mutableListOf<JsonTranslationTree>()
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        val name = parser.currentName()
                        val nameStart = parser.currentTokenLocation().charOffset.toInt()
                        parser.nextToken()
                        children += read(parser, text, name, TextSpan(nameStart, nameEnd(text, nameStart)))
                    }
                    JsonTranslationTree(ParsedNode(key, null, keySpan, TextSpan(start, end(parser))), children, isRoot, arb)
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

        /**
         * The offset right after the key starting at [start]: past its closing quote, double or
         * single, or past the identifier of a JSON5 unquoted key.
         */
        private fun nameEnd(text: String, start: Int): Int {
            val quote = text[start]
            var i = start + 1
            if (quote != '"' && quote != '\'') {
                while (i < text.length && Character.isJavaIdentifierPart(text[i])) i++
                return i
            }
            while (text[i] != quote) i += if (text[i] == '\\') 2 else 1
            return i + 1
        }
    }
}
