package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

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
        // YAML types stay inside this visitor: the platform reflects on the inspection class's
        // own methods (`getDeclaredMethods`) to save the inspection profile, and one of them
        // naming a YAML class fails with NoClassDefFoundError when the YAML plugin is disabled.
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonFile -> reportDuplicates(
                        PsiTreeUtil.findChildrenOfType(element, JsonProperty::class.java).mapNotNull { property ->
                            val value = property.value as? JsonStringLiteral ?: return@mapNotNull null
                            value.value to property.nameElement
                        },
                        holder
                    )
                    is YAMLFile -> reportDuplicates(
                        PsiTreeUtil.findChildrenOfType(element, YAMLKeyValue::class.java).mapNotNull { keyValue ->
                            val value = keyValue.value as? YAMLScalar ?: return@mapNotNull null
                            value.textValue to (keyValue.key ?: return@mapNotNull null)
                        },
                        holder
                    )
                }
            }
        }
    }

    /** Reports every key of [values] — (text, key element) — whose non-blank text another key holds too. */
    private fun reportDuplicates(values: List<Pair<String, PsiElement>>, holder: ProblemsHolder) {
        values.filter { it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .values.filter { it.size > 1 }
            .forEach { duplicates -> duplicates.forEach { keyElement -> holder.registerProblem(keyElement, MESSAGE) } }
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.duplicate.message")
    }
}
