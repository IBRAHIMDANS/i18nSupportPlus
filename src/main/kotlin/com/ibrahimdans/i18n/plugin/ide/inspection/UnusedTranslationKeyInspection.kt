package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class UnusedTranslationKeyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nUnusedKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR

        // One cache for the whole file: its properties share their prefixes almost entirely,
        // so the dynamic-head search runs a handful of times rather than once per key.
        val heads = mutableMapOf<String, Set<String>>()

        return object : PsiElementVisitor() {
        // YAML types stay inside this visitor: the platform reflects on the inspection class's
        // own methods (`getDeclaredMethods`) to save the inspection profile, and one of them
        // naming a YAML class fails with NoClassDefFoundError when the YAML plugin is disabled.
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        if (element.value !is JsonStringLiteral) return
                        check(element, element.nameElement, element.nameElement, holder, heads)
                    }
                    is YAMLKeyValue -> {
                        if (element.value !is YAMLScalar) return
                        check(element, element, element.key ?: return, holder, heads)
                    }
                }
            }
        }
    }

    /**
     * Reports the leaf key [declaration] when nothing refers to it: no reference search hit on
     * [declaration], no resolving reference held by [named] — the element carrying the key's
     * name — and no dynamic key reaching it. The problem sits on [anchor].
     */
    private fun check(
        declaration: PsiElement,
        named: PsiElement,
        anchor: PsiElement,
        holder: ProblemsHolder,
        heads: MutableMap<String, Set<String>>,
    ) {
        val hasRefs = ReadAction.compute<Boolean, RuntimeException> {
            TranslationKeyUsages.count(declaration, named, limit = 1) > 0
        }
        if (!hasRefs && !TranslationKeyUsages.reachedDynamically(named, heads)) {
            holder.registerProblem(anchor, MESSAGE, DeleteUnusedKeyFix())
        }
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.unused.message")
    }
}

private class DeleteUnusedKeyFix : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.unused.fix.name")
    override fun getFamilyName(): String = getName()

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = when (val parent = descriptor.psiElement.parent) {
            is JsonProperty -> parent
            is YAMLKeyValue -> parent
            else -> descriptor.psiElement
        }
        // Removes the separating comma too: a bare JsonProperty.delete()
        // leaves `{,"b":…}` behind and corrupts the file.
        deletePropertyAndSeparator(target)
    }
}
