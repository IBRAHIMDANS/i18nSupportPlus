package com.ibrahimdans.i18n.extract.nextintl

import com.ibrahimdans.i18n.extract.i18next.ARGUMENTS
import com.ibrahimdans.i18n.extract.i18next.CALL_EXPRESSION
import com.ibrahimdans.i18n.extract.i18next.TEMPLATE_STRING
import com.ibrahimdans.i18n.extract.i18next.TERNARY_EXPRESSION
import com.ibrahimdans.i18n.extract.i18next.arguments
import com.ibrahimdans.i18n.extract.i18next.calleeText
import com.ibrahimdans.i18n.extract.i18next.isQuotedString
import com.ibrahimdans.i18n.extract.i18next.items
import com.ibrahimdans.i18n.extract.i18next.propertyOf
import com.ibrahimdans.i18n.extract.i18next.scopesAround
import com.ibrahimdans.i18n.extract.i18next.unquoted
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The key of a string literal passed to next-intl's `t`, on a tree-sitter TSX tree: `t('title')` once
 * `const t = useTranslations('Home')` or `const t = await getTranslations('Home')` — or
 * `getTranslations({ locale, namespace: 'Home' })` — has bound it, and its methods `t.rich`,
 * `t.markup`, `t.raw` and `t.has`.
 *
 * The plugin's `ReactUseTranslationHookExtractor` and `NextIntlMethodExtractor` in one: messages live
 * in one file per locale, so the hook's argument is the path of the object holding the keys, a key
 * prefix — `Home.title` — not a namespace. A `t` bound by no next-intl hook is left to the other
 * extractors, whatever it is called.
 *
 * The bindings of the file being read are kept until a node of another file comes: one walk per
 * scope, not one per call.
 */
class NextIntlKeyExtractor : CodeKeyExtractor {

    @Volatile
    private var bindings: FileBindings? = null

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val call = firstArgumentCallOf(node) ?: return null
        val name = translatorOf(call) ?: return null
        val (scopes, root) = scopesAround(call)
        val file = bindings?.takeIf { it.root == root } ?: FileBindings(root).also { bindings = it }
        val hook = scopes.firstNotNullOfOrNull { file.of(it)[name] } ?: return null
        val prefix = namespaceOf(hook.arguments().firstOrNull())?.takeIf { it.isNotBlank() }
        return RawKey(listOf(KeyElement.literal(node.unquoted())), emptyList(), listOfNotNull(prefix?.let(KeyElement::literal)))
    }

    /** The hook calls of one parsed file, by scope and by the name each binds. */
    private class FileBindings(val root: CodeNode) {

        private val byScope = ConcurrentHashMap<CodeNode, Map<String, CodeNode>>()

        fun of(scope: CodeNode): Map<String, CodeNode> = byScope.getOrPut(scope) { bindingsOf(scope) }
    }

    companion object {

        /** `t.has(key)` asks whether the message exists: a usage, never a missing key. */
        const val HAS = "has"

        private val METHODS = setOf("rich", "markup", "raw", HAS)
        private val HOOKS = setOf("useTranslations", "getTranslations")

        private const val IDENTIFIER = "identifier"
        private const val MEMBER_EXPRESSION = "member_expression"
        private const val AWAIT_EXPRESSION = "await_expression"
        private const val PARENTHESIZED_EXPRESSION = "parenthesized_expression"
        private const val VARIABLE_DECLARATOR = "variable_declarator"

        /** The nodes a `const` declared in them is local to, as i18next's hook lookup reads them. */
        private val SCOPES = setOf(
            "program", "arrow_function", "function_declaration", "function_expression", "method_definition",
            "generator_function_declaration", "generator_function",
        )

        /**
         * The call whose first argument holds [node], whatever it calls: the rule of i18next's
         * `translationCallOf`, without its names — next-intl's `t` may be called anything.
         */
        private fun firstArgumentCallOf(node: CodeNode): CodeNode? {
            var child = node
            var current = node.parent
            while (current != null && current.type != ARGUMENTS) {
                if (current.type == TEMPLATE_STRING) return null
                if (current.type == TERNARY_EXPRESSION && current.children.firstOrNull() == child) return null
                child = current
                current = current.parent
            }
            if (current == null || current.items().firstOrNull() != child) return null
            return current.parent?.takeIf { it.type == CALL_EXPRESSION }
        }

        /** The name of the `t` [call] calls: `t` of `t('k')` and of `t.rich('k')`, null for `toast.t('k')`. */
        private fun translatorOf(call: CodeNode): String? {
            val callee = call.children.firstOrNull() ?: return null
            if (callee.type == IDENTIFIER) return callee.text
            if (callee.type != MEMBER_EXPRESSION) return null
            val parts = callee.children
            val target = parts.firstOrNull()?.takeIf { it.type == IDENTIFIER } ?: return null
            return target.text.takeIf { parts.lastOrNull()?.text in METHODS }
        }

        /** Each name a declarator of [scope] binds to a next-intl hook, `const t = useTranslations(…)`, the first winning. */
        private fun bindingsOf(scope: CodeNode): Map<String, CodeNode> {
            val bindings = mutableMapOf<String, CodeNode>()
            val pending = ArrayDeque(scope.children)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                if (node.type == VARIABLE_DECLARATOR) hookBinding(node)?.let { (name, hook) -> bindings.putIfAbsent(name, hook) }
                if (node.type !in SCOPES) pending.addAll(node.children)
            }
            return bindings
        }

        private fun hookBinding(declarator: CodeNode): Pair<String, CodeNode>? {
            val parts = declarator.children
            val name = parts.firstOrNull()?.takeIf { it.type == IDENTIFIER } ?: return null
            val hook = callOf(parts.lastOrNull())?.takeIf { it.calleeText() in HOOKS } ?: return null
            return name.text to hook
        }

        /** The call behind an initializer, seen through `await` and parentheses. */
        private fun callOf(expression: CodeNode?): CodeNode? = when (expression?.type) {
            CALL_EXPRESSION -> expression
            AWAIT_EXPRESSION, PARENTHESIZED_EXPRESSION -> callOf(expression.items().lastOrNull { it.type != "await" })
            else -> null
        }

        /** `'Home'`, or the `namespace` of `{ locale, namespace: 'Home' }`. */
        private fun namespaceOf(argument: CodeNode?): String? {
            val literal = propertyOf(argument, "namespace") ?: argument
            return literal?.takeIf { it.isQuotedString() }?.unquoted()
        }
    }
}
