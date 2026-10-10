package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

class UnusedTranslationKeyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nUnusedKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR

        // One cache for the whole file: its properties share their prefixes almost entirely,
        // so the dynamic-head search runs a handful of times rather than once per key.
        val heads = mutableMapOf<String, Set<String>>()

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                if (entry.literal == null) return
                // JSON references sit on the property's name, YAML ones on the key-value itself.
                val named = if (TranslationPsi.isYaml(element)) element else entry.keyElement
                check(element, named, entry.keyElement, holder, heads)
            }
        }
    }

    /**
     * Reports the leaf key [declaration] when nothing refers to it: no reference search hit on
     * [declaration], no resolving reference held by [named] — the element carrying the key's
     * name — no call site naming it under a key prefix or an `ns` option, no dynamic key reaching
     * it, and no rule of the project's [KeepList] keeping it.
     * The problem sits on [anchor].
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
        if (hasRefs || TranslationKeyUsages.kept(named) || TranslationKeyUsages.indirectCount(named) > 0 ||
            TranslationKeyUsages.reachedDynamically(named, heads)) return
        val key = ReadAction.compute<String, RuntimeException> {
            TranslationKeyUsages.keyOf(named, Settings.getInstance(named.project).config())
        }
        holder.registerProblem(anchor, MESSAGE, DeleteUnusedKeyFix(), KeepKeyQuickFix(key))
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.unused.message")
    }
}

private class DeleteUnusedKeyFix : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.unused.fix.name")
    override fun getFamilyName(): String = getName()

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val parent = descriptor.psiElement.parent
        val target = if (parent != null && TranslationPsi.nameOf(parent) != null) parent else descriptor.psiElement
        // Removes the separating comma too: a bare JsonProperty.delete()
        // leaves `{,"b":…}` behind and corrupts the file.
        deletePropertyAndSeparator(target)
    }
}
