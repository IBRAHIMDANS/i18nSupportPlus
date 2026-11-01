package com.ibrahimdans.i18n.extensions.technology.i18next

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * What a project's i18next initialisation states: the options object of `i18next.init({…})`, however
 * the instance is reached — `i18n.use(initReactI18next).init({…})`, `createInstance().init({…})` or
 * `createInstance({…})`.
 *
 * Only literal values are kept: the file is read without running Node, so `defaultNS: NAMESPACES[0]`
 * or `fallbackLng: getLocale()` is left out, and so is `keySeparator: false`.
 */
data class I18nextInitOptions(
    val defaultNs: List<String> = emptyList(),
    val ns: List<String> = emptyList(),
    val fallbackNs: List<String> = emptyList(),
    val fallbackLng: String? = null,
    val keySeparator: String? = null,
    val nsSeparator: String? = null,
    val pluralSeparator: String? = null,
    val contextSeparator: String? = null,
)

/**
 * Finds and reads the `init` call of [I18nextInitOptions] in a JavaScript or TypeScript file.
 *
 * A file is an initialisation file when it imports `i18next` and calls `init` (or `createInstance`)
 * with an object literal: the call is matched by its shape, not by the file's name, which differs
 * from one project to the next (`src/i18n.ts`, `src/i18n/index.ts`, `src/lib/i18n.ts`…).
 */
object I18nextInitReader {

    /** The words an initialisation file holds in code, for the word index to narrow the search. */
    val CALL_NAMES = listOf("init", "createInstance")

    /** The module an initialisation file imports. */
    const val MODULE = "i18next"

    /** The options [file] passes to i18next, or null when it initialises nothing. Cached on the file. */
    fun read(file: PsiFile): I18nextInitOptions? =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(optionsObject(file)?.let(::readOptions), file)
        }

    /** The object literal [file] passes to `init` or `createInstance`, when it imports i18next. */
    internal fun optionsObject(file: PsiFile): JSObjectLiteralExpression? {
        if (!importsI18next(file)) return null
        return PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java).firstNotNullOfOrNull { call ->
            val name = (call.methodExpression as? JSReferenceExpression)?.referenceName
            if (name in CALL_NAMES) call.arguments.firstOrNull() as? JSObjectLiteralExpression else null
        }
    }

    /** `import i18n from 'i18next'` — not `import type`, which brings no instance — or `require('i18next')`. */
    private fun importsI18next(file: PsiFile): Boolean =
        PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).any {
            it.fromClause?.referenceText?.trim('\'', '"', '`') == MODULE && !it.text.startsWith("import type")
        } || PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java).any { call ->
            (call.methodExpression as? JSReferenceExpression)?.referenceName == "require" &&
                (call.arguments.firstOrNull() as? JSLiteralExpression)?.stringValue == MODULE
        }

    internal fun readOptions(options: JSObjectLiteralExpression): I18nextInitOptions = I18nextInitOptions(
        defaultNs = strings(options.findProperty("defaultNS")?.value),
        ns = strings(options.findProperty("ns")?.value),
        fallbackNs = strings(options.findProperty("fallbackNS")?.value),
        fallbackLng = strings(options.findProperty("fallbackLng")?.value).firstOrNull(),
        keySeparator = string(options.findProperty("keySeparator")?.value),
        nsSeparator = string(options.findProperty("nsSeparator")?.value),
        pluralSeparator = string(options.findProperty("pluralSeparator")?.value),
        contextSeparator = string(options.findProperty("contextSeparator")?.value),
    )

    /** `'common'` or `['common', 'shared']`, in order; anything computed, even one entry, reads as nothing. */
    private fun strings(value: JSExpression?): List<String> = when (value) {
        is JSLiteralExpression -> listOfNotNull(string(value))
        is JSArrayLiteralExpression -> value.expressions.map { string(it) }.takeIf { null !in it }?.filterNotNull().orEmpty()
        else -> emptyList()
    }

    private fun string(value: JSExpression?): String? =
        (value as? JSLiteralExpression)?.takeIf { it.isQuotedLiteral }?.stringValue?.takeIf { it.isNotEmpty() }
}
