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
 * `t` called is bound by `const { t } = useTranslation(ns, { keyPrefix })`, or renamed there
 * (`const { t: tCommon } = …`, then `tCommon('save')`), the key takes the hook's
 * namespaces and key prefix; an explicit `t(key, { ns })` option overrides the namespaces either way.
 *
 * The hook bindings of the file being read are kept until a node of another file comes: one walk
 * per scope, not one per call.
 */
class TranslationCallKeyExtractor(private val functionNames: Set<String> = I18NEXT_FUNCTIONS) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean =
        node.isQuotedString() && callTo(node) != null

    @Volatile
    private var bindings: HookBindings? = null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val call = callTo(node) ?: return null
        return keyAt(call, listOf(KeyElement.literal(node.unquoted())))
    }

    /** The translation call whose first argument holds [node]: to one of the [functionNames], or to a renamed `t`. */
    internal fun callTo(node: CodeNode): CodeNode? {
        val call = callOf(node) ?: return null
        if (call.calleeText() in functionNames) return call
        val (scopes, root) = scopesAround(call)
        return call.takeIf { bindingsOf(root).isRenamed(it, scopes) }
    }

    /**
     * The key made of [elements] read at [call]: the namespaces of its `{ ns }` option or of the hook binding its function,
     * the hook's key prefix, the `context` option. Shared with
     * [SelectorCallKeyExtractor] and [TemplateCallKeyExtractor].
     */
    internal fun keyAt(call: CodeNode, elements: List<KeyElement>): RawKey {
        val hookArguments = hookOf(call)?.arguments().orEmpty()
        val prefix = propertyOf(hookArguments.getOrNull(1), "keyPrefix")
            ?.takeIf { it.isQuotedString() }
            ?.unquoted()
            ?.takeIf { it.isNotBlank() }
        return RawKey(
            elements,
            namespacesOption(call).ifEmpty { stringsOf(hookArguments.firstOrNull()) },
            listOfNotNull(prefix?.let(KeyElement::literal)),
            context = contextOption(call),
        )
    }

    private fun hookOf(call: CodeNode): CodeNode? {
        val (scopes, root) = scopesAround(call)
        return bindingsOf(root).hookOf(call, scopes)
    }

    private fun bindingsOf(root: CodeNode): HookBindings =
        bindings?.takeIf { it.root == root } ?: HookBindings(root).also { bindings = it }
}

/** The functions `I18NextTechnology` publishes: the translation functions of an i18next project, from its preset. */
val I18NEXT_FUNCTIONS: Set<String> = FrameworkPresets.I18NEXT.callNames.toSet()
