package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.isDirectOrConfiguredCall
import com.ibrahimdans.i18n.extensions.lang.js.jsRuleDecision
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * i18next's selector API (`enableSelector`, since i18next 25.4): the key is the path the arrow
 * function passed to `t` reads on its parameter — `t($ => $.button.save)` reads `button.save`,
 * `t($ => $.errors['not-found'])` reads `errors.not-found`.
 *
 * The element claimed is the arrow's body, the whole member chain, as i18next records every member
 * read and joins them with the key separator. The call is the hook's `t` or any other translation
 * function, read as a string key is: the hook's namespaces and `keyPrefix`, an explicit `{ ns }`.
 *
 * Under a hook declaring several namespaces, a first segment naming one of the others routes the
 * rest of the path to it, as i18next's `keysFromSelector` does: `useTranslation(['common', 'auth'])`
 * then `t($ => $.auth.login)` reads `login` in `auth`. The first namespace is never routed to. The
 * `"strict"` mode, which routes every first segment, is a type option the plugin does not read.
 *
 * Only an arrow passed first to a translation call, or in the array passed first (fallback keys),
 * counts: Redux's `useSelector(state => state.user.name)` is no key.
 */
class SelectorKeyExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = pathOf(element) != null

    override fun extract(element: PsiElement): RawKey {
        val path = pathOf(element) ?: return RawKey(emptyList())
        val base = HOOK.extract(element)
        val explicitNamespaces = OptionsExtractor.extractNamespaces(element).isNotEmpty()
        val routed = !explicitNamespaces && path.size > 1 && path.first() in base.arguments.drop(1)
        val keyPath = if (routed) path.drop(1) else path
        val separator = Settings.getInstance(element.project).config().keySeparator.ifEmpty { DEFAULT_SEPARATOR }
        return base.copy(
            keyElements = listOf(KeyElement.literal(keyPath.joinToString(separator))),
            arguments = if (routed) listOf(path.first()) else base.arguments,
        )
    }

    /**
     * True when [element] is a string read as a member inside a selector, `'not-found'` in
     * `$.errors['not-found']`: a segment of the selector's key, never a key of its own.
     */
    fun isSegmentLiteral(element: PsiElement): Boolean {
        val literal = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression ?: return false
        var access: PsiElement = (literal.parent as? JSIndexedPropertyAccessExpression)?.takeIf { it.indexExpression === literal } ?: return false
        while (true) {
            val parent = access.parent
            if (parent is JSReferenceExpression && parent.qualifier === access ||
                parent is JSIndexedPropertyAccessExpression && parent.qualifier === access) access = parent
            else return pathOf(access) != null
        }
    }

    /**
     * The members [element] reads on the parameter of the arrow it is the body of, `[button, save]`,
     * or null when [element] is no such body of a selector passed to a translation call.
     */
    private fun pathOf(element: PsiElement): List<String>? {
        if (element !is JSReferenceExpression && element !is JSIndexedPropertyAccessExpression) return null
        val arrow = element.parent as? JSFunction ?: return null
        if (!arrow.isArrowFunction) return null
        val parameter = arrow.parameters.singleOrNull()?.name ?: return null
        val segments = ArrayDeque<String>()
        var current: PsiElement = element
        while (true) {
            when (current) {
                is JSReferenceExpression -> {
                    val name = current.referenceName ?: return null
                    val qualifier = current.qualifier
                    if (qualifier == null) {
                        if (name != parameter || segments.isEmpty()) return null
                        break
                    }
                    segments.addFirst(name)
                    current = qualifier
                }
                is JSIndexedPropertyAccessExpression -> {
                    val index = current.indexExpression as? JSLiteralExpression ?: return null
                    if (!index.isQuotedLiteral) return null
                    segments.addFirst(index.stringValue ?: return null)
                    current = current.qualifier ?: return null
                }
                else -> return null
            }
        }
        return segments.toList().takeIf { isPassedToTranslationCall(arrow) }
    }

    /**
     * Whether [arrow] is the first argument of a translation call, or an item of the array passed
     * first: a name a technology publishes (or a rule includes), in a module allowing i18next.
     */
    private fun isPassedToTranslationCall(arrow: JSFunction): Boolean {
        val argument = (arrow.parent as? JSArrayLiteralExpression) ?: arrow
        val arguments = argument.parent as? JSArgumentList ?: return false
        if (arguments.arguments.firstOrNull() !== argument) return false
        val method = (arguments.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
        if (!ModulePresets.allows(ModulePresets.presetOf(arrow), I18NEXT)) return false
        when (jsRuleDecision(arrow)) {
            RuleDecision.INCLUDE -> return true
            RuleDecision.EXCLUDE -> return false
            RuleDecision.NONE -> {}
        }
        val names = ModulePresets.restrict(Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }, ModulePresets.presetOf(arrow))
        return method.referenceName in names.map { it.substringAfterLast('.') } && isDirectOrConfiguredCall(arrow, names)
    }

    private companion object {
        val HOOK = ReactUseTranslationHookExtractor()
        const val I18NEXT = "i18next"
        /** What i18next joins a selector's path with when no key separator is set (`keySeparator: false`). */
        const val DEFAULT_SEPARATOR = "."
    }
}
