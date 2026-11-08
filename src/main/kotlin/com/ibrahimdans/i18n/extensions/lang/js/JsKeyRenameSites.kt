package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.ParaglideMessageExtractor
import com.ibrahimdans.i18n.plugin.ide.actions.KeyRenameSites
import com.ibrahimdans.i18n.plugin.ide.actions.KeyRenameSites.Rewrite
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * JavaScript's [KeyRenameSites]: a react-i18next `keyPrefix` — `{ keyPrefix: 'button' }` or
 * `'button.primary'` in a `useTranslation` hook — and a Paraglide member, `m.hello`, rewritten to
 * the index form `m["a.b"]` when the renamed key is no identifier, or back to `m.key` when it becomes one.
 */
object JsKeyRenameSites : KeyRenameSites {

    private const val KEY_PREFIX = "keyPrefix"
    private const val USE_TRANSLATION = "useTranslation"
    private const val PARAGLIDE = "paraglide"
    private val PARAGLIDE_MESSAGE = ParaglideMessageExtractor()
    private val IDENTIFIER = Regex("[A-Za-z_$][\\w$]*")

    override fun prefixRewrite(element: PsiElement, path: List<String>, newName: String, namespaces: List<String>, config: Config): Rewrite? {
        val string = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return null
        if (!string.isQuotedLiteral) return null
        val property = string.parent as? JSProperty ?: return null
        if (property.name != KEY_PREFIX) return null
        val call = (property.parent as? JSObjectLiteralExpression)?.parent?.parent as? JSCallExpression ?: return null
        if (call.methodExpression?.text != USE_TRANSLATION) return null
        val hookNamespace = when (val first = call.arguments.firstOrNull()) {
            is JSLiteralExpression -> first.stringValue
            is JSArrayLiteralExpression -> (first.expressions.firstOrNull() as? JSLiteralExpression)?.stringValue
            else -> null
        }
        val inNamespace = if (hookNamespace == null) namespaces.any { it in config.defaultNamespaces() } else hookNamespace in namespaces
        if (!inNamespace) return null
        val prefix = string.stringValue ?: return null
        val segments = prefix.split(config.keySeparator).toMutableList()
        if (segments.size < path.size || segments.take(path.size) != path) return null
        segments[path.size - 1] = newName
        val text = string.text
        return Rewrite(string, "${text.first()}${segments.joinToString(config.keySeparator)}${text.last()}")
    }

    /** The Paraglide member at or just above [element] — its name, its index literal — in a module allowing Paraglide. */
    override fun messageMemberAt(element: PsiElement): PsiElement? =
        generateSequence(element) { it.parent }.take(3).firstOrNull(PARAGLIDE_MESSAGE::canExtract)
            ?.takeIf { ModulePresets.allows(ModulePresets.presetOf(it), PARAGLIDE) }

    override fun messageKeyOf(member: PsiElement): String? = PARAGLIDE_MESSAGE.extract(member).keyElements.singleOrNull()?.text

    override fun messageMemberRewrite(member: PsiElement, renamedKey: String): Rewrite? {
        val qualifier = when (member) {
            is JSReferenceExpression -> member.qualifier
            is JSIndexedPropertyAccessExpression -> member.qualifier
            else -> null
        }?.text ?: return null
        return Rewrite(member, if (IDENTIFIER.matches(renamedKey)) "$qualifier.$renamedKey" else "$qualifier[\"$renamedKey\"]")
    }
}
