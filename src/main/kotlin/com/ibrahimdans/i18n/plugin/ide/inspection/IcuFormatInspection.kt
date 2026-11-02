package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.check.IcuIssue
import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

private fun checkIcuValue(holder: ProblemsHolder, element: PsiElement, value: String, requiresOne: Boolean) {
    if (!ValueCheck.balanced(value)) {
        holder.registerProblem(element, PluginBundle.message("inspection.icu.unbalanced"))
        return
    }
    for (issue in ValueCheck.icuIssues(value, requiresOne)) {
        val message = when (issue.lacking) {
            IcuIssue.Lacking.OTHER -> PluginBundle.message("inspection.icu.missing.other", issue.block)
            IcuIssue.Lacking.ONE_OR_ZERO -> PluginBundle.message("inspection.icu.plural.forms")
        }
        holder.registerProblem(element, message)
    }
}

class IcuFormatInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nIcuFormat"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val source = TranslationFileScope.sourceOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val requiresOne = ValueCheck.pluralNeedsOne(source.localeLabel())
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                checkIcuValue(holder, entry.literal ?: return, entry.text ?: return, requiresOne)
            }
        }
    }
}
