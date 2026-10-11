package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.technology.transloco.TranslocoScopes
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

private val TRANSLOCO_METHODS = setOf("translate", "selectTranslate")

/**
 * The key of a Transloco service call: `this.translocoService.translate('key')`,
 * `transloco.selectTranslate('key')`.
 *
 * Like [NgxTranslateExtractor], the call is told apart by its qualifier, which must name the
 * service (`transloco` in it, any case): `translate` alone is too common a method name to claim.
 */
class TranslocoExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return false
        if (call.arguments.firstOrNull() !== element) return false
        val method = call.methodExpression as? JSReferenceExpression ?: return false
        if (method.referenceName !in TRANSLOCO_METHODS) return false
        val qualifier = method.qualifier?.text ?: return false
        return qualifier.contains("transloco", ignoreCase = true)
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as? JSLiteralExpression)?.stringValue ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(value)))
    }
}

/**
 * The key of `{{ 'key' | transloco }}` in an Angular template, recognised like
 * [AngularTranslatePipeExtractor] recognises `| translate`: on the text of the two closest
 * ancestors, with no compile-time dependency on the Angular plugin.
 */
class TranslocoPipeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isStringLiteral) return false
        return generateSequence(element.parent) { it.parent }
            .take(2)
            .any { PIPE.containsMatchIn(it.text) }
    }

    override fun extract(element: PsiElement): RawKey =
        RawKey(listOf(KeyElement.literal(element.text.trim().removeSurrounding("\"").removeSurrounding("'"))))

    private companion object {
        val PIPE = Regex("""\|\s*transloco\b""")
    }
}

/**
 * The key of `t('title')` under `*transloco="let t; read: 'admin'"`: Transloco prepends the `read`
 * value to every key the directive's `t` translates, so the call names `admin.title`.
 *
 * The value is a key prefix whose first segment may name a scope ([TranslocoScopes]):
 *  - `read: 'admin'`, `admin` a scope: the key `title` of the scope `admin`, which is given as the
 *    namespace the call works in, the way a hook's namespace is;
 *  - `read: 'admin.users'`, `admin` a scope: the key `users.title` of that scope;
 *  - `read: 'dashboard'`, no such scope: the global key `dashboard.title`.
 *
 * The directive publishing the function closest to the call decides: an inner `*transloco="let t"`
 * without `read` hides an outer one, and the call is then left to the generic `t` extraction.
 * A directive naming its function otherwise (`let tr`) is matched on that name.
 */
class TranslocoReadExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = literalOf(element)?.let(::readOf) != null

    override fun extract(element: PsiElement): RawKey {
        val literal = literalOf(element) ?: return RawKey(emptyList())
        val key = listOf(KeyElement.literal(literal.stringValue.orEmpty()))
        val read = readOf(literal) ?: return RawKey(key)
        val segments = read.split('.').filter { it.isNotEmpty() }
        val scope = segments.firstOrNull()?.takeIf { it in TranslocoScopes.scopes(literal.project) }
        val prefix = (if (scope != null) segments.drop(1) else segments).joinToString(".")
        return RawKey(
            key,
            listOfNotNull(scope),
            if (prefix.isEmpty()) emptyList() else listOf(KeyElement.literal(prefix)),
        )
    }

    /** The literal itself, or the one holding the leaf completion is asked at. */
    private fun literalOf(element: PsiElement): JSLiteralExpression? =
        (element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression)?.takeIf { it.isQuotedLiteral }

    /** The `read` of the directive publishing the function [element] is the first argument of, or null. */
    private fun readOf(element: JSLiteralExpression): String? {
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return null
        if (call.arguments.firstOrNull() !== element) return null
        val method = call.methodExpression as? JSReferenceExpression ?: return null
        if (method.qualifier != null) return null
        val name = method.referenceName ?: return null
        val directive = enclosingTags(call).firstNotNullOfOrNull { directiveOf(it, name) } ?: return null
        return READ.find(directive)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
    }

    /** The `*transloco` value of [tag], when it publishes a function called [name]. */
    private fun directiveOf(tag: XmlTag, name: String): String? {
        val value = tag.getAttribute(DIRECTIVE)?.value ?: return null
        return value.takeIf { LET.findAll(it).any { match -> match.groupValues[1] == name } }
    }

    /** The tags enclosing [element], innermost first, reached through the template's injection host if any. */
    private fun enclosingTags(element: PsiElement): Sequence<XmlTag> {
        val start = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
            ?: InjectedLanguageManager.getInstance(element.project).getInjectionHost(element)
                ?.let { PsiTreeUtil.getParentOfType(it, XmlTag::class.java, false) }
        return generateSequence(start) { PsiTreeUtil.getParentOfType(it, XmlTag::class.java) }
    }

    private companion object {
        const val DIRECTIVE = "*transloco"
        val LET = Regex("""\blet\s+([\w$]+)""")
        val READ = Regex("""\bread\s*:\s*(['"])(.*?)\1""")
    }
}
