package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * The key of a next-intl `t` method: `t.rich('terms', { link: (chunks) => <a>{chunks}</a> })`,
 * `t.markup('terms', { … })`, `t.raw('list')` and `t.has('title')`, where `t` comes from
 * `useTranslations('Home')` or `await getTranslations('Home')`.
 *
 * The generic `t` extraction matches a call by its method name, which here is `rich`, and keeps
 * a qualified call out unless its whole text is a published name. These calls are therefore read
 * here, only when the qualifier is a plain `t` that [ReactUseTranslationHookExtractor] traces to a
 * next-intl hook — `toast.rich('x')` stays out — and the key is read as that extractor reads
 * `t('title')`, the hook's namespace as key prefix.
 */
class NextIntlMethodExtractor(private val methods: Set<String> = ALL) : KeyExtractor {

    companion object {
        /** The methods returning the message: its key is checked like `t('key')`'s. */
        val MESSAGE_METHODS = setOf("rich", "markup", "raw")

        /** `t.has(key)` asks whether the message exists: a usage, never a missing key. */
        const val HAS = "has"

        /** `t.raw(key)` returns the message as written, an object or an array as well as a string. */
        const val RAW = "raw"

        val ALL = MESSAGE_METHODS + HAS

        private val HOOK = ReactUseTranslationHookExtractor()
    }

    override fun canExtract(element: PsiElement): Boolean {
        val literal = element as? JSLiteralExpression
            ?: element.parent as? JSLiteralExpression
            ?: return false
        val arguments = literal.parent as? JSArgumentList ?: return false
        if (arguments.arguments.firstOrNull() !== literal) return false
        val method = (arguments.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
        if (method.referenceName !in methods) return false
        val qualifier = method.qualifier as? JSReferenceExpression ?: return false
        return qualifier.qualifier == null && HOOK.canExtract(element)
    }

    override fun extract(element: PsiElement): RawKey = HOOK.extract(element)
}
