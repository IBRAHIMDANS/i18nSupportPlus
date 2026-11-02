package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.openapi.project.DumbService

class PlaceholderConsistencyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nPlaceholderConsistency"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file
        if (TranslationFileScope.sourceOf(file) == null) return PsiElementVisitor.EMPTY_VISITOR
        val refTranslations: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                check(entry.literal ?: return, entry.text ?: return, holder, refTranslations)
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
        if (!ValueCheck.balanced(value)) {
            holder.registerProblem(literal, PluginBundle.message("inspection.placeholder.unbalanced"))
            return
        }
        val refValue = refTranslations[TranslationFileKeys.keyOf(literal)] ?: return
        for (missing in ValueCheck.missingPlaceholders(value, refValue)) {
            holder.registerProblem(literal, PluginBundle.message("inspection.placeholder.missing", missing))
        }
    }
}
