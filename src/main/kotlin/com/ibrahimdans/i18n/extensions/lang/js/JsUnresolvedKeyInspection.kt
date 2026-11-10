package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspectionBase
import com.intellij.lang.Language
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.psi.PsiElement

/**
 * The unresolved key inspection for JavaScript and its dialects — TypeScript, JSX, TSX, and the
 * script and template expressions of Vue, Svelte and Astro — and for the markup of a Vue
 * template (`v-t`, `<i18n-t keypath>`).
 *
 * Each element is read as the annotators read it: a JavaScript element by [JsxLang], as the
 * annotator declared on JavaScript, a Vue element by [JsLang], as the one declared on Vue. The
 * inspection is declared without a language so that one inspection covers both.
 */
class JsUnresolvedKeyInspection : UnresolvedKeyInspectionBase() {

    private val script = KeyCheck(JsxLang())
    private val vueMarkup = KeyCheck(JsLang())

    override fun checkOf(element: PsiElement): KeyCheck? {
        val language = element.language
        return when {
            language.isKindOf(JavascriptLanguage) -> script
            language.isOf(VUE) -> vueMarkup
            else -> null
        }
    }

    private fun Language.isOf(id: String): Boolean = generateSequence(this) { it.baseLanguage }.any { it.id == id }

    private companion object {
        const val VUE = "Vue"
    }
}
