package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Flags leaf translation entries that share the same (non-blank) value within a single
 * locale file. Identical values often signal copy-paste mistakes or keys that should be
 * merged, helping translators keep a file DRY.
 *
 * The check is scoped to one file: the same value living in two different locale files is
 * expected and never flagged. Blank values are ignored — they are covered by
 * [EmptyTranslationValueInspection].
 */
class DuplicateTranslationValueInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nDuplicateValue"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is PsiFile) return
                val values = TranslationPsi.allEntries(element).mapNotNull { entry -> entry.text?.let { it to entry.keyElement } }
                reportDuplicates(values, holder)
            }
        }
    }

    /** Reports every key of [values] — (text, key element) — whose non-blank text another key holds too. */
    private fun reportDuplicates(values: List<Pair<String, PsiElement>>, holder: ProblemsHolder) {
        duplicated(values).forEach { keyElement -> holder.registerProblem(keyElement, MESSAGE) }
    }

    internal companion object {
        private val MESSAGE: String get() = PluginBundle.message("inspection.duplicate.message")

        /**
         * The keys of [values] — (text, key) pairs of one locale file — whose non-blank text another
         * key holds too. The rule the table's *Duplicate values* filter applies as well.
         */
        fun <K> duplicated(values: List<Pair<String, K>>): List<K> =
            values.filter { it.first.isNotBlank() }
                .groupBy({ it.first }, { it.second })
                .values.filter { it.size > 1 }
                .flatten()
    }
}
