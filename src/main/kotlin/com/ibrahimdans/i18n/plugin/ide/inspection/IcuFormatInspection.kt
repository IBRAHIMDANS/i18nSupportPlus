package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.ibrahimdans.i18n.plugin.utils.localeLabel
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

/**
 * Languages whose only CLDR cardinal category is `other`: a plural in them carries no `one` or
 * `zero` form, and asking for one is a false warning on every Japanese or Chinese catalogue.
 */
private val OTHER_ONLY_LANGUAGES = setOf(
    "bo", "dz", "id", "ig", "ii", "ja", "jbo", "jv", "kde", "kea", "km", "ko", "lkt", "lo", "ms",
    "my", "nqo", "osa", "sah", "ses", "sg", "su", "th", "to", "tpi", "vi", "wo", "yo", "yue", "zh"
)

private fun checkIcuValue(holder: ProblemsHolder, element: PsiElement, value: String, requiresOne: Boolean) {
    if (!areBalanced(value)) {
        holder.registerProblem(element, PluginBundle.message("inspection.icu.unbalanced"))
        return
    }
    for (match in ICU_BLOCK_REGEX.findAll(value)) {
        val type = match.groupValues[1]
        val body = match.groupValues[2]
        val forms = Regex("""(\w+)\s*\{""").findAll(body).map { it.groupValues[1] }.toSet()

        if ("other" !in forms) {
            holder.registerProblem(element, PluginBundle.message("inspection.icu.missing.other", type))
        }
        if (type == "plural" && requiresOne && "one" !in forms && "zero" !in forms) {
            holder.registerProblem(element, PluginBundle.message("inspection.icu.plural.forms"))
        }
    }
}

class IcuFormatInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nIcuFormat"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val source = TranslationFileScope.sourceOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val language = source.localeLabel().substringBefore('-').substringBefore('_').lowercase()
        val requiresOne = language !in OTHER_ONLY_LANGUAGES
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val literal = element.value as? JsonStringLiteral ?: return
                        checkIcuValue(holder, literal, literal.value, requiresOne)
                    }
                    is YAMLKeyValue -> {
                        val scalar = element.value as? YAMLScalar ?: return
                        checkIcuValue(holder, scalar, scalar.textValue, requiresOne)
                    }
                }
            }
        }
    }
}
