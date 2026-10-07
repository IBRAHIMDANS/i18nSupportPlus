package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * A visitor for the inspections comparing each value of a translation file with the same key's
 * value in the reference locale: [check] receives the string literal, its value and the reference
 * value, for every key both files hold.
 *
 * Nothing is visited in a file that is not a translation source, nor in the reference file
 * itself (it has no counterpart). JSON and YAML are read alike, through [TranslationPsi], which
 * names no YAML class — see its documentation.
 */
internal fun referenceValueVisitor(
    holder: ProblemsHolder,
    check: (literal: PsiElement, value: String, reference: String) -> Unit,
): PsiElementVisitor {
    if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
    val file = holder.file
    if (TranslationFileScope.sourceOf(file) == null) return PsiElementVisitor.EMPTY_VISITOR
    val references: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }

    fun visit(literal: PsiElement, value: String) {
        if (references.isEmpty()) return
        val reference = references[TranslationFileKeys.keyOf(literal)] ?: return
        check(literal, value, reference)
    }

    return object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val entry = TranslationPsi.entryOf(element) ?: return
            visit(entry.literal ?: return, entry.text ?: return)
        }
    }
}
