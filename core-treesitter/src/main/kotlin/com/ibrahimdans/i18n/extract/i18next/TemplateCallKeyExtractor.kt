package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The key of a template literal passed to an i18next translation function, `t(`ns:status.${s}`)`,
 * on a tree-sitter TSX tree.
 *
 * The elements are those the plugin's `TemplateKeyExtractor` reads off the PSI tokens — the
 * backquotes, each `$`, `{` and `}`, the literal parts, each `${…}` expression as a template — so the
 * key parser's `ExpressionNormalizer` reduces both to the same key. Like the PSI extractor, it takes
 * the `{ ns }` option and never the hook's namespaces.
 */
class TemplateCallKeyExtractor(private val functionNames: Set<String> = I18NEXT_FUNCTIONS) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = node.type == TEMPLATE_STRING && translationCallOf(node, functionNames) != null

    override fun extract(node: CodeNode): RawKey? {
        if (node.type != TEMPLATE_STRING) return null
        val call = translationCallOf(node, functionNames) ?: return null
        return RawKey(elementsOf(node), namespacesOption(call))
    }

    /** One literal per run of fragments and escapes, as the PSI has one token per template part. */
    private fun elementsOf(template: CodeNode): List<KeyElement> {
        val elements = mutableListOf<KeyElement>()
        val part = StringBuilder()
        fun flush() {
            if (part.isNotEmpty()) elements += KeyElement.literal(part.toString())
            part.clear()
        }
        for (child in template.children) when (child.type) {
            BACKQUOTE -> { flush(); elements += KeyElement.literal(BACKQUOTE) }
            TEMPLATE_SUBSTITUTION -> {
                flush()
                val expression = child.text.removePrefix("\${").removeSuffix("}")
                elements += listOf(KeyElement.literal("$"), KeyElement.literal("{"), KeyElement.template(expression), KeyElement.literal("}"))
            }
            else -> part.append(child.text)
        }
        flush()
        return elements
    }

    private companion object {
        const val BACKQUOTE = "`"
    }
}
