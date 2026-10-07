package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags leaf translation entries whose value is empty or blank.
 *
 * KeysSynchronizer (and the CreateMissingKeys quickfix) intentionally insert missing keys
 * with an empty value, leaving no static signal about which keys still need to be filled.
 * This inspection closes that loop by highlighting empty leaf values per locale file.
 *
 * Only leaf string/scalar values are considered; object- and mapping-valued properties
 * (which group nested keys) are skipped.
 */
class EmptyTranslationValueInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nEmptyValue"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                check(entry.text ?: return, entry.keyElement, holder)
            }
        }
    }

    /** Reports [keyElement] when its [value] is blank. */
    private fun check(value: String, keyElement: PsiElement, holder: ProblemsHolder) {
        if (value.isBlank()) holder.registerProblem(keyElement, MESSAGE)
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.empty.message")
    }
}
