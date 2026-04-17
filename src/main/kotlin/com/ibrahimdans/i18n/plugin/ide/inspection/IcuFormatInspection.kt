package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val ICU_BLOCK_REGEX = Regex("""\{\s*\w+\s*,\s*(plural|select)\s*,([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""")

private fun areBalanced(text: String): Boolean {
    var depth = 0
    for (ch in text) {
        when (ch) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0
}

private fun checkIcuValue(holder: ProblemsHolder, element: PsiElement, value: String) {
    if (!areBalanced(value)) {
        holder.registerProblem(element, "ICU format error: unbalanced braces in translation value")
        return
    }
    for (match in ICU_BLOCK_REGEX.findAll(value)) {
        val type = match.groupValues[1]
        val body = match.groupValues[2]
        val forms = Regex("""(\w+)\s*\{""").findAll(body).map { it.groupValues[1] }.toSet()

        if ("other" !in forms) {
            holder.registerProblem(element, "ICU format error: '$type' block is missing the required 'other' form")
        }
        if (type == "plural" && "one" !in forms && "zero" !in forms) {
            holder.registerProblem(element, "ICU format error: 'plural' block must have at least 'one' or 'zero' form")
        }
    }
}

class IcuFormatInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nIcuFormat"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val literal = element.value as? JsonStringLiteral ?: return
                        checkIcuValue(holder, literal, literal.value)
                    }
                    is YAMLKeyValue -> {
                        val scalar = element.value as? YAMLScalar ?: return
                        checkIcuValue(holder, scalar, scalar.textValue)
                    }
                }
            }
        }
}
