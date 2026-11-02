package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

private fun checkIcuValue(holder: ProblemsHolder, element: PsiElement, value: String) {
    if (!ValueCheck.balanced(value)) {
        holder.registerProblem(element, PluginBundle.message("inspection.icu.unbalanced"))
        return
    }
    for (issue in ValueCheck.icuIssues(value)) {
        holder.registerProblem(element, PluginBundle.message("inspection.icu.missing.other", issue.block))
    }
}

class IcuFormatInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nIcuFormat"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                checkIcuValue(holder, entry.literal ?: return, entry.text ?: return)
            }
        }
    }
}
