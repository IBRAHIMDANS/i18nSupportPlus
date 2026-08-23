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

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonFile -> checkJsonFile(element, holder)
                    is YAMLFile -> checkYamlFile(element, holder)
                }
            }
        }
    }

    private fun checkJsonFile(file: JsonFile, holder: ProblemsHolder) {
        val byValue = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .mapNotNull { property ->
                val value = property.value as? JsonStringLiteral ?: return@mapNotNull null
                if (value.value.isBlank()) null else value.value to property.nameElement
            }
            .groupBy({ it.first }, { it.second })

        byValue.values.filter { it.size > 1 }.forEach { duplicates ->
            duplicates.forEach { nameElement -> holder.registerProblem(nameElement, MESSAGE) }
        }
    }

    private fun checkYamlFile(file: YAMLFile, holder: ProblemsHolder) {
        val byValue = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .mapNotNull { keyValue ->
                val value = keyValue.value as? YAMLScalar ?: return@mapNotNull null
                val keyElement = keyValue.key ?: return@mapNotNull null
                if (value.textValue.isBlank()) null else value.textValue to keyElement
            }
            .groupBy({ it.first }, { it.second })

        byValue.values.filter { it.size > 1 }.forEach { duplicates ->
            duplicates.forEach { keyElement -> holder.registerProblem(keyElement, MESSAGE) }
        }
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.duplicate.message")
    }
}
