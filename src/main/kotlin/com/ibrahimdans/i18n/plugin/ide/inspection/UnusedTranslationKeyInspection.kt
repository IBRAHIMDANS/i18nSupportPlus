package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

class UnusedTranslationKeyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nUnusedKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (!mayHoldTranslations(holder.file)) return PsiElementVisitor.EMPTY_VISITOR

        // One cache for the whole file: its properties share their prefixes almost entirely,
        // so the dynamic-head search runs a handful of times rather than once per key.
        val heads = mutableMapOf<String, Set<String>>()

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                if (entry.literal == null) return
                // A root `$schema` (Paraglide's message files) is no key, so never an unused one.
                if (element is JsonProperty && JsonElementTree.isRootSchema(element)) return
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

    /**
     * Whether [file] may be a translation file: a source the plugin reads, or — in a project that
     * does not say where its translations are — any JSON or YAML file but a tool's configuration.
     *
     * The other translation inspections require a source ([TranslationFileScope.sourceOf]); this one
     * also checks the files a namespace names (`test.json` for `t('test:greeting')`) with no locale
     * in their path. It used to check every file, which a headless run — Qodana, `inspect.sh` — made
     * plain: every key of `package.json` and `tsconfig.json` reported as an unused translation.
     */
    private fun mayHoldTranslations(file: PsiFile): Boolean {
        // An inlang project's `project.inlang/settings.json` (Paraglide) configures, it translates nothing.
        if (file.originalFile.virtualFile?.parent?.name == INLANG_PROJECT) return false
        if (TranslationFileScope.sourceOf(file) != null) return true
        if (TOOL_CONFIGURATION.matches(file.name)) return false
        val config = Settings.getInstance(file.project).config()
        return config.translationsRoot.isBlank() && config.modules.isEmpty()
    }

    private companion object {
        val MESSAGE: String get() = PluginBundle.message("inspection.unused.message")

        const val INLANG_PROJECT = "project.inlang"

        /** The configuration files of the JavaScript and PHP tools, which hold keys that are not translations. */
        val TOOL_CONFIGURATION = Regex(
            """(package(-lock)?|composer|[tj]sconfig(\..+)?|.+\.config|\.?(eslintrc|prettierrc|babelrc|stylelintrc)(\..+)?|angular|nx|lerna|turbo|vercel|renovate)\.(json|ya?ml)"""
        )
    }
}

/**
 * Deletes the unused key and its review in that file's locale ([ReviewMigration]) in one command,
 * so one Ctrl+Z restores both. A module without `.i18n-reviews/` has no review file touched.
 * Other locales keep their key, so they keep their review.
 */
private class DeleteUnusedKeyFix : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.unused.fix.name")
    override fun getFamilyName(): String = getName()

    /**
     * The preview deletes the property in the file's copy and nothing else: applied to the copy,
     * [applyFix] would rewrite the real review file, which [ReviewMigration] finds from the original.
     */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        deletePropertyAndSeparator(targetOf(previewDescriptor.psiElement ?: return IntentionPreviewInfo.EMPTY))
        return IntentionPreviewInfo.DIFF
    }

    // The write action is opened here: global or not depends on the key having a review.
    override fun startInWriteAction(): Boolean = false

    /**
     * Without a review, deletes the key in the command the platform opened for the quick fix, as it
     * always did.
     *
     * With one, the key and its review go in a command of their own, global and undone without a
     * confirmation, like the tool window's deletions: one Ctrl+Z restores both. That command cannot
     * nest in the platform's: a nested command keeps the outer one's confirmation policy (the
     * default), and a command touching two files is global, so that Ctrl+Z would ask "Undo …?".
     * It therefore runs right after the platform's command ends, which is left empty.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = targetOf(descriptor.psiElement ?: return)
        // Before the deletion: the review's key is rebuilt from the property's PSI.
        val reviews = ReviewMigration.spotsOf(listOf(target))
        if (reviews.isEmpty()) {
            WriteCommandAction.runWriteCommandAction(project, getName(), null, { delete(target) }, target.containingFile)
            return
        }
        ApplicationManager.getApplication().invokeLater({
            if (!target.isValid) return@invokeLater
            WriteCommandAction.writeCommandAction(project, target.containingFile)
                .withName(getName())
                .withGlobalUndo()
                .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
                .run<RuntimeException> {
                    delete(target)
                    ReviewMigration.remove(project, reviews)
                }
        }, project.disposed)
    }

    /**
     * Removes the separating comma too: a bare JsonProperty.delete()
     * leaves `{,"b":…}` behind and corrupts the file.
     */
    private fun delete(target: PsiElement) {
        if (target.isValid) deletePropertyAndSeparator(target)
    }

    /** The property holding the reported key name, or the reported element itself (a YAML key-value). */
    private fun targetOf(element: PsiElement): PsiElement {
        val parent = element.parent
        return if (parent != null && TranslationPsi.nameOf(parent) != null) parent else element
    }
}
