package com.ibrahimdans.i18n.format

import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.ParsedNodeTree
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * A TypeScript or JavaScript translation catalogue read with tree-sitter: the object literal the
 * file exports, what the plugin's `TsLocalizationTree` reads over the PSI.
 *
 * The object is the one of `export default { … }` (or of the `const` it names), else of
 * `module.exports = { … }`, else of the only `export const x = { … }`; `as const`, `satisfies …`
 * and parentheses around it are looked through. Keys are identifiers, strings or numbers; values
 * are strings, templates without `${…}`, numbers, booleans, `null`, arrays (a leaf holding their
 * source text, as in JSON) or nested objects.
 *
 * Only a catalogue written as literals can be checked: a spread, a computed key or a value built at
 * run time hides keys no reading can list, and the check would report them missing. [read] then
 * answers [Reading.Partial] rather than a tree with holes.
 */
class TsCatalogTree private constructor(node: ParsedNode, children: List<TsCatalogTree>?) : ParsedNodeTree(node, children) {

    /** What [read] found in a file. */
    sealed interface Reading {
        /** The exported object, every part of it a literal. */
        class Catalogue(val tree: TsCatalogTree) : Reading

        /** The exported object holds [construct], at [line] (1-based), which only running the code would resolve. */
        data class Partial(val construct: String, val line: Int) : Reading

        /** No exported object literal: a code file, not a catalogue. */
        data object NoCatalogue : Reading
    }

    companion object {

        /** The catalogue [text] exports. */
        fun read(text: String): Reading {
            val root = TreeSitterCodeNode.parseTsx(text)
            val exported = exportedObject(root) ?: return Reading.NoCatalogue
            return try {
                Reading.Catalogue(objectTree(exported, key = null, keySpan = null))
            } catch (e: NotLiteral) {
                Reading.Partial(e.construct, text.lineAt(e.node.span.start))
            }
        }

        /** The tree of [text]'s catalogue, null when it has none or one [read] finds [Reading.Partial]. */
        fun parse(text: String): TsCatalogTree? = (read(text) as? Reading.Catalogue)?.tree

        private fun exportedObject(program: CodeNode): CodeNode? {
            val statements = program.children
            val consts = statements.flatMap { constsOf(if (it.type == EXPORT_STATEMENT) it.childOfType(LEXICAL_DECLARATION) else it) }.toMap()

            statements.filter { it.type == EXPORT_STATEMENT && it.children.any { child -> child.type == DEFAULT } }
                .firstNotNullOfOrNull { export ->
                    val value = export.children.lastOrNull { it.type !in PUNCTUATION }?.let(::unwrapped)
                    if (value?.type == IDENTIFIER) consts[value.text]?.let(::unwrapped) else value
                }
                ?.takeIf { it.type == OBJECT }
                ?.let { return it }

            statements.firstNotNullOfOrNull { statement -> moduleExportsOf(statement) }?.let { return it }

            return statements.filter { it.type == EXPORT_STATEMENT }
                .flatMap { constsOf(it.childOfType(LEXICAL_DECLARATION)).map { (_, value) -> unwrapped(value) } }
                .filter { it.type == OBJECT }
                .singleOrNull()
        }

        /** Name -> value of the `const` declarators of [declaration], none when it is no declaration. */
        private fun constsOf(declaration: CodeNode?): List<Pair<String, CodeNode>> {
            if (declaration?.type != LEXICAL_DECLARATION) return emptyList()
            return declaration.children.filter { it.type == VARIABLE_DECLARATOR }.mapNotNull { declarator ->
                val name = declarator.children.firstOrNull()?.takeIf { it.type == IDENTIFIER } ?: return@mapNotNull null
                val value = declarator.children.lastOrNull()?.takeIf { it != name } ?: return@mapNotNull null
                name.text to value
            }
        }

        /** The object of `module.exports = { … }`. */
        private fun moduleExportsOf(statement: CodeNode): CodeNode? {
            val assignment = statement.takeIf { it.type == EXPRESSION_STATEMENT }?.childOfType(ASSIGNMENT_EXPRESSION) ?: return null
            if (assignment.children.firstOrNull()?.text != "module.exports") return null
            return assignment.children.lastOrNull()?.let(::unwrapped)?.takeIf { it.type == OBJECT }
        }

        /** [node] without the `as …`, `satisfies …` or parentheses around it. */
        private fun unwrapped(node: CodeNode): CodeNode = when (node.type) {
            AS_EXPRESSION, SATISFIES_EXPRESSION -> node.children.firstOrNull()?.let(::unwrapped) ?: node
            PARENTHESIZED_EXPRESSION -> node.children.firstOrNull { it.type !in PUNCTUATION }?.let(::unwrapped) ?: node
            else -> node
        }

        private fun objectTree(node: CodeNode, key: String?, keySpan: TextSpan?): TsCatalogTree {
            val children = node.children.filter { it.type !in PUNCTUATION }.map { member ->
                when (member.type) {
                    PAIR -> pairTree(member)
                    SPREAD_ELEMENT -> throw NotLiteral("spread", member)
                    SHORTHAND_PROPERTY -> throw NotLiteral("shorthand property", member)
                    METHOD_DEFINITION -> throw NotLiteral("method", member)
                    else -> throw NotLiteral(constructOf(member), member)
                }
            }
            return TsCatalogTree(ParsedNode(key, null, keySpan, node.span), children)
        }

        private fun pairTree(pair: CodeNode): TsCatalogTree {
            val keyNode = pair.children.first()
            val key = when (keyNode.type) {
                PROPERTY_IDENTIFIER, NUMBER -> keyNode.text
                STRING -> decoded(keyNode)
                COMPUTED_PROPERTY_NAME -> throw NotLiteral("computed key", keyNode)
                else -> throw NotLiteral(constructOf(keyNode), keyNode)
            }
            val valueNode = pair.children.last().let(::unwrapped)
            return when (valueNode.type) {
                OBJECT -> objectTree(valueNode, key, keyNode.span)
                STRING -> leaf(key, decoded(valueNode), keyNode, valueNode)
                TEMPLATE_STRING -> {
                    valueNode.children.firstOrNull { it.type == TEMPLATE_SUBSTITUTION }?.let { throw NotLiteral("template with \${…}", it) }
                    leaf(key, decoded(valueNode), keyNode, valueNode)
                }
                in SCALARS -> leaf(key, valueNode.text, keyNode, valueNode)
                else -> throw NotLiteral(if (valueNode.type == ERROR) SYNTAX_ERROR else "${valueNode.type} value", valueNode)
            }
        }

        private fun leaf(key: String, value: String, keyNode: CodeNode, valueNode: CodeNode): TsCatalogTree =
            TsCatalogTree(ParsedNode(key, value, keyNode.span, valueNode.span), null)

        /** A string or template literal's content, its escapes resolved. */
        private fun decoded(literal: CodeNode): String = unescape(literal.text.substring(1, literal.text.length - 1))

        /** JavaScript string escapes: `\n`, `\'`, `é`, `\u{1F600}`, `\x41`, a line continuation. */
        internal fun unescape(raw: String): String {
            if ('\\' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i + 1 == raw.length) {
                    out.append(c); i++; continue
                }
                val next = raw[i + 1]
                i += 2
                when (next) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'v' -> out.append('\u000B')
                    '0' -> out.append('\u0000')
                    '\n' -> {}
                    'x' -> { out.append(raw.substring(i, i + 2).toInt(16).toChar()); i += 2 }
                    'u' -> if (raw.getOrNull(i) == '{') {
                        val end = raw.indexOf('}', i)
                        out.appendCodePoint(raw.substring(i + 1, end).toInt(16)); i = end + 1
                    } else {
                        out.append(raw.substring(i, i + 4).toInt(16).toChar()); i += 4
                    }
                    else -> out.append(next)
                }
            }
            return out.toString()
        }

        /** What a report calls an unexpected [node]: tree-sitter's type, or a syntax error for the nodes it recovered around. */
        private fun constructOf(node: CodeNode): String = if (node.type == ERROR) SYNTAX_ERROR else node.type

        private fun String.lineAt(offset: Int): Int = 1 + (0 until offset).count { this[it] == '\n' }

        private fun CodeNode.childOfType(type: String): CodeNode? = children.firstOrNull { it.type == type }

        private class NotLiteral(val construct: String, val node: CodeNode) : RuntimeException(null, null, false, false)

        private const val EXPORT_STATEMENT = "export_statement"
        private const val DEFAULT = "default"
        private const val LEXICAL_DECLARATION = "lexical_declaration"
        private const val VARIABLE_DECLARATOR = "variable_declarator"
        private const val EXPRESSION_STATEMENT = "expression_statement"
        private const val ASSIGNMENT_EXPRESSION = "assignment_expression"
        private const val AS_EXPRESSION = "as_expression"
        private const val SATISFIES_EXPRESSION = "satisfies_expression"
        private const val PARENTHESIZED_EXPRESSION = "parenthesized_expression"
        private const val IDENTIFIER = "identifier"
        private const val OBJECT = "object"
        private const val PAIR = "pair"
        private const val SPREAD_ELEMENT = "spread_element"
        private const val SHORTHAND_PROPERTY = "shorthand_property_identifier"
        private const val METHOD_DEFINITION = "method_definition"
        private const val PROPERTY_IDENTIFIER = "property_identifier"
        private const val COMPUTED_PROPERTY_NAME = "computed_property_name"
        private const val STRING = "string"
        private const val NUMBER = "number"
        private const val TEMPLATE_STRING = "template_string"
        private const val TEMPLATE_SUBSTITUTION = "template_substitution"

        private const val ERROR = "ERROR"
        private const val SYNTAX_ERROR = "syntax error"

        private val SCALARS = setOf(NUMBER, "true", "false", "null", "array")

        /** Braces, commas, parentheses, keywords and comments: what sits between the parts read. */
        private val PUNCTUATION = setOf("{", "}", "(", ")", ",", ";", "export", "default", "comment")
    }
}
