package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
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

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nEmptyValue"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
        // YAML types stay inside this visitor: the platform reflects on the inspection class's
        // own methods (`getDeclaredMethods`) to save the inspection profile, and one of them
        // naming a YAML class fails with NoClassDefFoundError when the YAML plugin is disabled.
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val value = element.value as? JsonStringLiteral ?: return
                        check(value.value, element.nameElement, holder)
                    }
                    is YAMLKeyValue -> {
                        val value = element.value as? YAMLScalar ?: return
                        check(value.textValue, element.key ?: return, holder)
                    }
                }
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
