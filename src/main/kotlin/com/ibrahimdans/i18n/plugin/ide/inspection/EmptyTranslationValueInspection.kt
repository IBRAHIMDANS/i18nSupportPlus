package com.ibrahimdans.i18n.plugin.ide.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

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

    override fun getDisplayName(): String = "Empty translation value"
    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nEmptyValue"

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
        val value = property.value as? JsonStringLiteral ?: return
        if (value.value.isBlank()) {
            holder.registerProblem(property.nameElement, MESSAGE)
        }
    }

    private fun checkYamlKeyValue(keyValue: YAMLKeyValue, holder: ProblemsHolder) {
        val value = keyValue.value as? YAMLScalar ?: return
        val keyElement = keyValue.key ?: return
        if (value.textValue.isBlank()) {
            holder.registerProblem(keyElement, MESSAGE)
        }
    }

    private companion object {
        const val MESSAGE = "Translation value is empty"
    }
}
