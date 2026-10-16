package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.lang.js.ParaglideImports
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * Paraglide JS: the key is the member of the message object, not a string — `m.hello()` reads the
 * message `hello`, `m["user.profile.title"]()` the nested `user` → `profile` → `title`.
 *
 * The element claimed is the whole member access, `m.hello` or `m["…"]`: its text holds the key, so
 * the annotation lands on `hello` (or inside the quotes), never on `m.`. The call is not required —
 * `{ greeting: m.greeting }` passes the message along and still names it. Only a message object the
 * file imports from Paraglide counts ([ParaglideImports]): `obj.m.hello`, a local `m`, and
 * `m[variable]` are left alone.
 */
class ParaglideMessageExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = keyOf(element) != null

    override fun extract(element: PsiElement): RawKey =
        keyOf(element)?.let { RawKey(listOf(KeyElement.literal(it))) } ?: RawKey(emptyList())

    /** The key [element] names, or null when it is no member access of an imported message object. */
    private fun keyOf(element: PsiElement): String? = when (element) {
        is JSReferenceExpression -> element.referenceName?.takeIf { isMessageObject(element.qualifier) }
        is JSIndexedPropertyAccessExpression -> (element.indexExpression as? JSLiteralExpression)
            ?.takeIf { it.isQuotedLiteral && isMessageObject(element.qualifier) }
            ?.stringValue
        else -> null
    }

    /** Whether [qualifier] is a bare name the file binds Paraglide's message object to. */
    private fun isMessageObject(qualifier: PsiElement?): Boolean {
        val name = qualifier as? JSReferenceExpression ?: return false
        if (name.qualifier != null) return false
        val text = name.referenceName ?: return false
        return text in ParaglideImports.messageObjectNames(name)
    }
}
