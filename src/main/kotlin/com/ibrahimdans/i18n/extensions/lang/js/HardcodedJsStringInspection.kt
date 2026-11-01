package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a string a user reads, written in JavaScript or TypeScript outside JSX: passed to a function
 * of [functions] — `toast('Saved')`, `toast.error('Failed')`, `alert('Done')` — or held by a
 * property of [properties] — `{ title: 'Settings' }`. The *Extract i18n key* quick fix writes the
 * translation call as for any string.
 *
 * Off by default, and silent whenever in doubt: most strings of a program are code, so only these
 * two places are read, and both lists are options of the inspection. A string is reported when:
 *  - it is the direct argument of a call whose callee is a name of [functions], or starts or ends
 *    with one — `toast` matches `toast(…)`, `toast.success(…)` and `this.toast(…)`;
 *  - or it is the value of an object property named in [properties];
 *  - it holds a letter ([HardcodedTextRules.isTranslatable]) and the extraction accepts it, not yet
 *    extracted;
 *  - its file is a `.js`, `.ts`, `.jsx` or `.tsx` file: a Vue component's script, a template's
 *    expression have hard-coded text inspections of their own.
 */
class HardcodedJsStringInspection : LocalInspectionTool() {

    /** Functions whose string arguments are shown to the user, separated by commas. */
    @JvmField
    var functions: String = "alert, confirm, toast"

    /** Properties whose string values are shown to the user, separated by commas. */
    @JvmField
    var properties: String = "title, label, placeholder"

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nHardcodedJsString"

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.string("functions", PluginBundle.message("inspection.hardcoded.js.string.option.functions"), 40),
        OptPane.string("properties", PluginBundle.message("inspection.hardcoded.js.string.option.properties"), 40),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file.name.substringAfterLast('.').lowercase() !in EXTENSIONS) return PsiElementVisitor.EMPTY_VISITOR
        val functions = names(functions)
        val properties = names(properties)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val literal = element as? JSLiteralExpression ?: return
                if (!isShown(literal, functions, properties)) return
                val token = literal.firstChild ?: return
                if (!EXTRACTOR.canExtract(token) || EXTRACTOR.isExtracted(token)) return
                if (!HardcodedTextRules.isTranslatable(literal.stringValue.orEmpty())) return
                holder.registerProblem(literal, PluginBundle.message("inspection.hardcoded.jsx.text.message"), ExtractTextQuickFix(token))
            }
        }
    }

    internal companion object {

        private val EXTRACTOR = JsTranslationExtractor()
        private val EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")

        /** The names of a comma-separated option, blanks ignored. */
        fun names(option: String): Set<String> = option.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Whether [literal], a quoted string, is an argument of a call of [functions] or the value of a property of [properties]. */
        fun isShown(literal: JSLiteralExpression, functions: Set<String>, properties: Set<String>): Boolean {
            if (!literal.isQuotedLiteral) return false
            val call = (literal.parent as? JSArgumentList)?.parent as? JSCallExpression
            if (call != null) {
                val callee = call.methodExpression?.text ?: return false
                return functions.any { callee == it || callee.endsWith(".$it") || callee.startsWith("$it.") }
            }
            val property = literal.parent as? JSProperty ?: return false
            return property.value === literal && property.name in properties
        }
    }
}
