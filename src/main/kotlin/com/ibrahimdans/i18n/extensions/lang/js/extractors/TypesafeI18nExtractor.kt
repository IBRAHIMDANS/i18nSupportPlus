package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * typesafe-i18n: the key is the path of members after the translation object, not a string —
 * `LL.home.title()` reads `home.title`, `$LL.nav.home()` the same in a Svelte component.
 *
 * The element claimed is the whole member chain, `LL.home.title`, and only its outermost access:
 * `LL.home` inside it is a level, not a key of its own. The call is not required — `LL.home.title`
 * passed along still names the message. Only a file importing typesafe-i18n's generated adapter or
 * utilities ([importsTypesafeI18n]) counts: an `LL` of any other library is left alone.
 */
class TypesafeI18nExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = keyOf(element) != null

    override fun extract(element: PsiElement): RawKey =
        keyOf(element)?.let { RawKey(listOf(KeyElement.literal(it))) } ?: RawKey(emptyList())

    /** The key [element] names, or null when it is no outermost member chain of the translation object. */
    private fun keyOf(element: PsiElement): String? {
        if (element !is JSReferenceExpression) return null
        if ((element.parent as? JSReferenceExpression)?.qualifier == element) return null
        val segments = pathOf(element) ?: return null
        return segments.joinToString(".").takeIf { importsTypesafeI18n(element) }
    }

    companion object {

        /** The names typesafe-i18n binds its translation object to: `LL`, and `$LL`, its Svelte store. */
        val ROOTS = setOf("LL", "\$LL")

        /** The generated adapters and utilities (`i18n-react`, `i18n-svelte`, `i18n-util`, …) or the package itself. */
        private val IMPORT = Regex("""(?:from|import|require\()\s*['"][^'"]*(?:/i18n-(?:react|svelte|vue|solid|angular|util|node)(?:\.[jt]sx?)?|typesafe-i18n)['"]""")

        /**
         * The members after the translation object, `[home, title]` for `LL.home.title`, or null when
         * [member] does not start with a bare `LL`/`$LL` or names no member after it.
         */
        fun pathOf(member: JSReferenceExpression): List<String>? {
            val segments = ArrayDeque<String>()
            var current: JSReferenceExpression = member
            while (true) {
                val name = current.referenceName ?: return null
                val qualifier = current.qualifier ?: return if (name in ROOTS && segments.isNotEmpty()) segments.toList() else null
                segments.addFirst(name)
                current = qualifier as? JSReferenceExpression ?: return null
            }
        }

        /** Whether [element]'s host file — the component, for an injected fragment — imports typesafe-i18n's code. */
        fun importsTypesafeI18n(element: PsiElement): Boolean {
            val file = element.hostFile() ?: return false
            return CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(imports(file), file) }
        }

        private fun imports(file: PsiFile): Boolean = IMPORT.containsMatchIn(file.text)
    }
}
