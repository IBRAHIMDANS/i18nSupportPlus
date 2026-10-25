package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.lang.js.AngularDirectiveExtractor
import com.ibrahimdans.i18n.extensions.lang.js.importsPackage
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

/** The functions `@jsverse/transloco` exports for use outside a template, in an injection context. */
private val TRANSLOCO_FUNCTIONS = setOf("translate", "translateSignal")

private val TRANSLOCO_PACKAGES = listOf("@jsverse/transloco", "@ngneat/transloco")

/**
 * The key of a Transloco service call: `this.translocoService.translate('key')`,
 * `transloco.selectTranslate('key')`, and of the standalone functions `translate('key')` and
 * `translateSignal('key')`.
 *
 * Like [NgxTranslateExtractor], a service call is told apart by its qualifier, which must name the
 * service (`transloco` in it, any case): `translate` alone is too common a method name to claim. For
 * the same reason a bare `translate('key')` is Transloco's only in a file importing Transloco.
 */
class TranslocoExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is JSLiteralExpression || !element.isQuotedLiteral) return false
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java) ?: return false
        if (call.arguments.firstOrNull() !== element) return false
        val method = call.methodExpression as? JSReferenceExpression ?: return false
        val qualifier = method.qualifier?.text
            ?: return method.referenceName in TRANSLOCO_FUNCTIONS && TRANSLOCO_PACKAGES.any { importsPackage(it, element) }
        if (method.referenceName !in TRANSLOCO_METHODS) return false
        return qualifier.contains("transloco", ignoreCase = true)
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as? JSLiteralExpression)?.stringValue ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(value)))
    }
}

/**
 * The key of Transloco's attribute directive: `<span transloco="home.title">` and
 * `<span [transloco]="'home.title'">`. The structural `<ng-template transloco let-t>` gives it no
 * value, and holds no key.
 */
class TranslocoDirectiveExtractor : AngularDirectiveExtractor("transloco")

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
 *
 * The prefix may also come from the `[translocoRead]` input on the directive's tag
 * (`<ng-container *transloco="let t" [translocoRead]="'admin'">`), read only when its value is a
 * string literal: a bound expression is not known before runtime, and the call is then left to the
 * generic `t` extraction. A `read:` in the directive wins over the input.
 *
 * Transloco 8 renames `read` to `prefix` (`prefix: 'admin'`, `[translocoPrefix]`): both are read the
 * same. And the input is only accepted by Angular on the directive's own tag, which the `*transloco`
 * sugar is not — `<ng-template transloco let-t [translocoRead]="'admin'">` is the form that compiles
 * (NG8002 otherwise), so a `transloco` attribute publishing its function through `let-t` counts too.
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
        val tag = enclosingTags(call).firstOrNull { publishes(it, name) } ?: return null
        val directive = tag.getAttribute(DIRECTIVE)?.value.orEmpty()
        return (READ.find(directive)?.groupValues?.get(2) ?: readInputOf(tag))?.takeIf { it.isNotBlank() }
    }

    /** Whether the directive of [tag] — `*transloco="let t"`, or `transloco` with `let-t` — publishes a function called [name]. */
    private fun publishes(tag: XmlTag, name: String): Boolean {
        // Searched by name: `getAttribute("let-t")` finds no Angular `let-` declaration.
        if (tag.getAttribute(TEMPLATE_DIRECTIVE) != null && tag.attributes.any { it.name == "let-$name" }) return true
        val value = tag.getAttribute(DIRECTIVE)?.value ?: return false
        return LET.findAll(value).any { match -> match.groupValues[1] == name }
    }

    /** The `[translocoRead]` or `[translocoPrefix]` of [tag] when it binds a string literal (`"'admin'"`), or null. */
    private fun readInputOf(tag: XmlTag): String? {
        val value = READ_INPUTS.firstNotNullOfOrNull { tag.getAttribute(it)?.value?.trim() } ?: return null
        return LITERAL.matchEntire(value)?.groupValues?.get(2)
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
        const val TEMPLATE_DIRECTIVE = "transloco"
        val LET = Regex("""\blet\s+([\w$]+)""")
        val READ = Regex("""\b(?:read|prefix)\s*:\s*(['"])(.*?)\1""")
        val READ_INPUTS = listOf("[translocoRead]", "[translocoPrefix]")
        val LITERAL = Regex("""(['"])([^'"]*)\1""")
    }
}
