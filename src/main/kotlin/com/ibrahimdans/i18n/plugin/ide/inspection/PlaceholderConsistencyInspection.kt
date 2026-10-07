package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.openapi.project.DumbService
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

private val PLACEHOLDER_REGEX = Regex("""\{[\w\d_]+\}|%[0-9]*\$?[sd]""")
private val UNCLOSED_BRACE_REGEX = Regex("""\{[^}]*$""", RegexOption.MULTILINE)
private val UNOPENED_BRACE_REGEX = Regex("""^[^{]*\}""", RegexOption.MULTILINE)

private fun extractPlaceholders(text: String): Set<String> =
    PLACEHOLDER_REGEX.findAll(text).map { it.value }.toSet()

private fun isSyntacticallyValid(text: String): Boolean =
    !UNCLOSED_BRACE_REGEX.containsMatchIn(text) && !UNOPENED_BRACE_REGEX.containsMatchIn(text)

class PlaceholderConsistencyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nPlaceholderConsistency"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file
        if (TranslationFileScope.sourceOf(file) == null) return PsiElementVisitor.EMPTY_VISITOR
        val refTranslations: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }

        return object : PsiElementVisitor() {
        // YAML types stay inside this visitor: the platform reflects on the inspection class's
        // own methods (`getDeclaredMethods`) to save the inspection profile, and one of them
        // naming a YAML class fails with NoClassDefFoundError when the YAML plugin is disabled.
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val literal = element.value as? JsonStringLiteral ?: return
                        check(literal, literal.value, holder, refTranslations)
                    }
                    is YAMLKeyValue -> {
                        val scalar = element.value as? YAMLScalar ?: return
                        check(scalar, scalar.textValue, holder, refTranslations)
                    }
                }
            }
        }
    }

    /** Checks the [value] written in [literal] against the same key's value in [refTranslations]. */
    private fun check(
        literal: PsiElement,
        value: String,
        holder: ProblemsHolder,
        refTranslations: Map<String, String>
    ) {
        if (!isSyntacticallyValid(value)) {
            holder.registerProblem(literal, PluginBundle.message("inspection.placeholder.unbalanced"))
            return
        }
        val currentPlaceholders = extractPlaceholders(value)
        if (currentPlaceholders.isEmpty()) return
        val key = TranslationFileKeys.keyOf(literal)
        val refValue = refTranslations[key] ?: return
        val refPlaceholders = extractPlaceholders(refValue)
        for (missing in refPlaceholders - currentPlaceholders) {
            holder.registerProblem(literal, PluginBundle.message("inspection.placeholder.missing", missing))
        }
    }
}
