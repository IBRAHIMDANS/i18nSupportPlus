package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The key of a string literal passed to an i18next translation function: `t('ns:a.b')`,
 * `i18n.t('a.b')`, on a tree-sitter TSX tree.
 *
 * The plugin's `ReactUseTranslationHookExtractor` and `StringLiteralKeyExtractor` in one: when the
 * `t` called is bound by `const { t } = useTranslation(ns, { keyPrefix })`, the key takes the hook's
 * namespaces and key prefix; an explicit `t(key, { ns })` option overrides the namespaces either way.
 */
class TranslationCallKeyExtractor(private val functionNames: Set<String> = I18NEXT_FUNCTIONS) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = node.isQuotedString() && translationCallOf(node, functionNames) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val call = translationCallOf(node, functionNames) ?: return null
        val hookArguments = hookOf(call)?.arguments().orEmpty()
        val prefix = propertyOf(hookArguments.getOrNull(1), "keyPrefix")
            ?.takeIf { it.isQuotedString() }
            ?.unquoted()
            ?.takeIf { it.isNotBlank() }
        return RawKey(
            listOf(KeyElement.literal(node.unquoted())),
            namespacesOption(call).ifEmpty { stringsOf(hookArguments.firstOrNull()) },
            listOfNotNull(prefix?.let(KeyElement::literal)),
        )
    }
}

/** The functions `I18NextTechnology` publishes: the translation functions of an i18next project. */
val I18NEXT_FUNCTIONS: Set<String> = setOf("t", "i18n.t", "i18next.t", "props.t", "this.props.t")
