package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class UnusedTranslationKeyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Unused translation key"
    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nUnusedKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> checkJsonProperty(element, holder)
                    is YAMLKeyValue -> checkYamlKeyValue(element, holder)
                }
            }
        }
    }

    private fun checkJsonProperty(property: JsonProperty, holder: ProblemsHolder) {
        if (property.value !is JsonStringLiteral) return
        val nameElement = property.nameElement
        val hasRefs = runReadAction {
            ReferencesSearch.search(property).findFirst() != null
                || nameElement.references.any { it.resolve() != null }
        }
        if (!hasRefs) {
            holder.registerProblem(nameElement, MESSAGE, DeleteUnusedKeyFix())
        }
    }

    private fun checkYamlKeyValue(keyValue: YAMLKeyValue, holder: ProblemsHolder) {
        if (keyValue.value !is YAMLScalar) return
        val keyElement = keyValue.key ?: return
        val hasRefs = runReadAction {
            ReferencesSearch.search(keyValue).findFirst() != null
                || keyValue.references.any { it.resolve() != null }
        }
        if (!hasRefs) {
            holder.registerProblem(keyElement, MESSAGE, DeleteUnusedKeyFix())
        }
    }

    private companion object {
        const val MESSAGE = "Translation key is never used in code"
    }
}

private class DeleteUnusedKeyFix : LocalQuickFix {

    override fun getName(): String = "Delete unused key"
    override fun getFamilyName(): String = "Delete unused key"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = when (val parent = descriptor.psiElement.parent) {
            is JsonProperty -> parent
            is YAMLKeyValue -> parent
            else -> descriptor.psiElement
        }
        target.delete()
    }
}
