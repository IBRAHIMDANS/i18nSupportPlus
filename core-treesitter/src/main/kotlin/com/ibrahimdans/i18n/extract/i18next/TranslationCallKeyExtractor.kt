package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The key of a string literal passed to an i18next translation function: `t('ns:a.b')`,
 * `i18n.t('a.b')`, on a tree-sitter TSX tree.
 *
 * The plugin's `ReactUseTranslationHookExtractor` and `StringLiteralKeyExtractor` in one: when the
 * `t` called is bound by `const { t } = useTranslation(ns, { keyPrefix })`, the key takes the hook's
 * namespaces and key prefix; an explicit `t(key, { ns })` option overrides the namespaces either way.
 *
 * The hook bindings of the file being read are kept until a node of another file comes: one walk
 * per scope, not one per call.
 */
class TranslationCallKeyExtractor(private val functionNames: Set<String> = I18NEXT_FUNCTIONS) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean =
        node.isQuotedString() && translationCallOf(node, functionNames) != null

    @Volatile
    private var bindings: HookBindings? = null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val call = translationCallOf(node, functionNames) ?: return null
        return keyAt(call, node.unquoted())
    }

    /**
     * [key] read at [call]: the namespaces of its `{ ns }` option or of the hook binding its function,
     * the hook's key prefix, the `context` option. Shared with [SelectorCallKeyExtractor].
     */
    internal fun keyAt(call: CodeNode, key: String): RawKey {
        val hookArguments = hookOf(call)?.arguments().orEmpty()
        val prefix = propertyOf(hookArguments.getOrNull(1), "keyPrefix")
            ?.takeIf { it.isQuotedString() }
            ?.unquoted()
            ?.takeIf { it.isNotBlank() }
        return RawKey(
            listOf(KeyElement.literal(key)),
            namespacesOption(call).ifEmpty { stringsOf(hookArguments.firstOrNull()) },
            listOfNotNull(prefix?.let(KeyElement::literal)),
            context = contextOption(call),
        )
    }

    private fun hookOf(call: CodeNode): CodeNode? {
        val (scopes, root) = scopesAround(call)
        val current = bindings?.takeIf { it.root == root } ?: HookBindings(root).also { bindings = it }
        return current.hookOf(call, scopes)
    }
}

/** The functions `I18NextTechnology` publishes: the translation functions of an i18next project, from its preset. */
val I18NEXT_FUNCTIONS: Set<String> = FrameworkPresets.I18NEXT.callNames.toSet()
