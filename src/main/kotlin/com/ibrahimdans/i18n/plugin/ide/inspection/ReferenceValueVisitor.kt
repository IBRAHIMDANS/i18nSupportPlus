package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * A visitor for the inspections comparing each value of a translation file with the same key's
 * value in the reference locale: [check] receives the string literal, its value and the reference
 * value, for every key both files hold.
 *
 * Nothing is visited in a file that is not a translation source, nor in the reference file
 * itself (it has no counterpart). JSON and YAML are read alike; the YAML types stay inside this
 * function rather than in an inspection class, whose members the platform reflects on to save
 * the inspection profile — see `InspectionYamlIsolationTest`.
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
            when (element) {
                is JsonProperty -> (element.value as? JsonStringLiteral)?.let { visit(it, it.value) }
                is YAMLKeyValue -> (element.value as? YAMLScalar)?.let { visit(it, it.textValue) }
            }
        }
    }
}
