package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

/**
 * How a JavaScript message names, writes and passes its variables — shared by the JSX text, the
 * template literal and the concatenation an extraction turns into a message.
 */
internal object MessageVariables {

    private const val I18N_JS = "i18n-js"

    /** Names of a size, which a translation reads as a count — and pluralises on. */
    private val COUNTS = setOf("length", "size", "count", "total")

    /**
     * How the technology of the module holding [element] writes a variable in a message: `{name}`
     * where it interpolates single braces (lingui, react-intl, vue-i18n, svelte-i18n — the list
     * [InterpolationArgumentsInspection] reads calls with), `%{name}` for i18n-js, and i18next's
     * `{{name}}` otherwise, a module without a preset included. A placeholder in another
     * technology's syntax is printed as is: `Hello {{name}}` on screen.
     */
    fun placeholderSyntax(element: PsiElement): (name: String) -> String {
        val preset = ModulePresets.presetOf(element)
        return when {
            preset == I18N_JS -> { name -> "%{$name}" }
            preset != null && preset in InterpolationArgumentsInspection.SINGLE_BRACE_FRAMEWORKS -> { name -> "{$name}" }
            else -> { name -> "{{$name}}" }
        }
    }

    /**
     * The variables of [expressions], in order, one per distinct expression: `user.name` reads
     * `name`, `files.length` reads `count`, anything else `value`, `value2`… — names the
     * extraction dialog lets the user change. Two expressions ending alike are told apart by a
     * number.
     */
    fun of(expressions: List<JSExpression>, placeholder: (String) -> String): List<MessageVariable> {
        val byExpression = linkedMapOf<String, String>()
        for (expression in expressions) {
            val text = expression.text
            if (text in byExpression) continue
            val base = baseName(expression)
            var name = base
            var index = 2
            while (name in byExpression.values) name = "$base${index++}"
            byExpression[text] = name
        }
        return byExpression.map { (expression, name) -> MessageVariable(name, expression, placeholder(name)) }
    }

    private fun baseName(expression: JSExpression): String {
        val reference = expression as? JSReferenceExpression ?: return "value"
        val name = reference.referenceName ?: return "value"
        return if (name in COUNTS) "count" else name
    }

    /** `, { name: user.name, count }` for a call, nothing without variables. */
    fun options(variables: List<MessageVariable>): String {
        if (variables.isEmpty()) return ""
        val entries = variables.joinToString(", ") { if (it.name == it.expression) it.name else "${it.name}: ${it.expression}" }
        return ", { $entries }"
    }
}
