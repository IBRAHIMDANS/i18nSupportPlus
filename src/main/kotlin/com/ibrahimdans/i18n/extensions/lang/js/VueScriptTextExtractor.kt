package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.lang.Language
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Extracts a string of a Vue component's `<script>` — read as [JsTranslationExtractor] reads one,
 * concatenations and template literals included — into the call that component reaches:
 *  - `t('…')` in `<script setup>`, whose `const { t } = useI18n()` *Internationalize…* adds when missing;
 *  - `this.$t('…')` in a method of the Options API (`methods`, `computed`, `data()`…), where `this`
 *    is the component.
 *
 * Anywhere else — the module's top level of a plain `<script>`, `setup()`, an arrow function at
 * the top — nothing reaches a translation function without more code: the string is not extracted.
 * Neither is a string that is Vue code by its place ([isVueCode]): `defineProps`, `emit('save')`,
 * the `name` or `props` of the component, a TypeScript literal type.
 */
internal class VueScriptTextExtractor : TranslationExtractor {

    private val js = JsTranslationExtractor()

    /** How the component's script reaches `t`. */
    enum class Style { SETUP, OPTIONS }

    override fun canExtract(element: PsiElement): Boolean {
        if (!isVue(element.containingFile) || !js.isStringToken(element)) return false
        val javaScript = Language.findLanguageByID("JavaScript") ?: return false
        if (!element.language.isKindOf(javaScript)) return false
        return styleOf(element) != null && !isVueCode(element)
    }

    override fun isExtracted(element: PsiElement): Boolean = js.isExtracted(element)

    override fun text(element: PsiElement): String = js.text(element)

    override fun textRange(element: PsiElement): TextRange = js.textRange(element)

    override fun variables(element: PsiElement): List<MessageVariable> = js.variables(element)

    override fun template(element: PsiElement): (argument: String) -> String {
        val call = call(element)
        val variables = variables(element)
        return { call(it, variables) }
    }

    override fun call(element: PsiElement): (argument: String, variables: List<MessageVariable>) -> String {
        val function = if (styleOf(element) == Style.OPTIONS) "this.\$t" else "t"
        return { argument, variables -> "$function($argument${MessageVariables.options(variables)})" }
    }

    companion object {

        /** Calls whose string arguments are Vue code, matched on the callee's last name. */
        private val VUE_CODE_CALLS = setOf(
            "defineProps", "defineEmits", "defineModel", "defineOptions", "defineSlots", "withDefaults",
            "emit", "\$emit", "inject", "provide", "resolveComponent", "push", "replace",
        )

        /** Properties of a component, or of a route, whose strings are code. */
        private val VUE_CODE_PROPERTIES = setOf("name", "props", "emits", "components", "inject", "path", "type", "model")

        /**
         * How [element]'s string would call `t`: [Style.SETUP] in `<script setup>`, [Style.OPTIONS]
         * in a method of the component — the first function around it that is not an arrow one
         * (an arrow keeps the method's `this`), not `setup()`, written in an object — else null.
         */
        fun styleOf(element: PsiElement): Style? {
            val script = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)?.takeIf { it.name == "script" } ?: return null
            if (script.getAttribute("setup") != null) return Style.SETUP
            var function = PsiTreeUtil.getParentOfType(element, JSFunction::class.java)
            while (function != null && function.isArrowFunction) function = PsiTreeUtil.getParentOfType(function, JSFunction::class.java)
            function ?: return null
            if (function.name == "setup") return null
            val owner = (function.parent as? JSProperty)?.parent ?: function.parent
            return if (owner is JSObjectLiteralExpression) Style.OPTIONS else null
        }

        /** A string of a `.vue` file that is code by its place, whatever it holds. */
        private fun isVueCode(element: PsiElement): Boolean {
            if (PsiTreeUtil.getParentOfType(element, TypeScriptType::class.java) != null) return true
            for (ancestor in generateSequence(element.parent) { it.parent }.takeWhile { it !is JSFunction && it !is XmlTag }) {
                val call = (ancestor as? JSArgumentList)?.parent as? JSCallExpression
                if ((call?.methodExpression as? JSReferenceExpression)?.referenceName in VUE_CODE_CALLS) return true
                if ((ancestor as? JSProperty)?.name in VUE_CODE_PROPERTIES) return true
            }
            return false
        }

        private fun isVue(file: PsiFile): Boolean = file.name.endsWith(".vue")
    }
}
