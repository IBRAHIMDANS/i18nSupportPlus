package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Flags, in a locale file, every key the reference locale's file for the same namespace does not
 * hold — `fr/common.json` holds `user.nmae`, `en/common.json` does not.
 *
 * The inverse of [MissingTranslationKeyInspection]. Such a key is a leftover of a rename made in
 * the reference only, or a typo in the locale: the code asks for the reference's spelling, so the
 * translation is never shown; and a key the code does use, absent from the reference, shows its
 * raw name in the reference language. [UnusedTranslationKeyInspection] only catches the keys that
 * are orphan **and** unused. The reference file itself is never inspected:
 * [TranslationFileKeys.referenceFileOf] finds no counterpart for it.
 *
 * The problem sits on the key's name — `b` for an orphan `a.b` under an `a` the reference has —
 * so the file is not underlined as a whole.
 *
 * Plural forms are compared as a group ([PluralKey.groupForms]): `ru` holding `item_few` is right
 * where `en` spells the same plural `item_one` / `item_other`, since the categories depend on the
 * language.
 */
class OrphanTranslationKeyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nOrphanKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiFile) checkFile(element, holder)
            }
        }
    }

    private fun checkFile(file: PsiFile, holder: ProblemsHolder) {
        val reference = TranslationFileKeys.referenceFileOf(file) ?: return
        val source = TranslationFileScope.sourceOf(file) ?: return
        val config = Settings.getInstance(file.project).config()
        if (config.ignoresLocale(source.localeLabel())) return
        val root = TranslationPsi.rootOf(file) ?: return
        val referenceRoot = TranslationPsi.rootOf(reference)
        val referenceLocale = TranslationFileScope.referenceLocaleFor(file, source)
        val referenceLeaves = TranslationFileKeys.translationLeaves(reference).keys

        for (path in orphanPaths(referenceLeaves, TranslationFileKeys.translationLeaves(file).keys, config.pluralSeparator)) {
            val entry = entryOf(root, path) ?: continue
            val key = path.joinToString(".")
            val message = PluginBundle.message("inspection.orphan.key.message", key, referenceLocale)
            val remove = RemoveOrphanKeyFix(key)
            if (referenceRoot != null && canHold(referenceRoot, path)) {
                holder.registerProblem(entry.keyElement, message, remove, AddToReferenceFix(path, referenceLocale))
            } else {
                // The path runs into a leaf of the reference (`user` is a string there): no key can
                // be added under it without overwriting a translation.
                holder.registerProblem(entry.keyElement, message, remove)
            }
        }
    }

    /** The key-value at [path] under [root], or null when the path leads nowhere. */
    private fun entryOf(root: PsiElement, path: List<String>) =
        path.dropLast(1).fold<String, PsiElement?>(root) { container, segment ->
            container?.let { TranslationPsi.childOf(it, segment) }?.let { TranslationPsi.valueOf(it.property) }
        }?.let { TranslationPsi.childOf(it, path.last()) }

    /** Whether every level of [path] that [referenceRoot] already holds is an object a key can go into. */
    private fun canHold(referenceRoot: PsiElement, path: List<String>): Boolean {
        var container: PsiElement = referenceRoot
        for (segment in path.dropLast(1)) {
            val entry = TranslationPsi.childOf(container, segment) ?: return true
            val value = TranslationPsi.valueOf(entry.property)
            if (!TranslationPsi.isContainer(value)) return false
            container = value!!
        }
        return true
    }

    internal companion object {

        /**
         * The paths of [currentLeaves] that [referenceLeaves] lacks, a plural form being covered by
         * its group: `item_few` is no orphan when the reference has a plural `item`, whatever forms
         * it spells it with.
         */
        fun orphanPaths(
            referenceLeaves: Collection<List<String>>,
            currentLeaves: Collection<List<String>>,
            pluralSeparator: String,
        ): List<List<String>> {
            val referenceKeys = referenceLeaves.mapTo(HashSet()) { it.joinToString(".") }
            val referencePlurals = PluralKey.groupForms(referenceKeys).filter { (base, forms) -> forms != listOf(base) }.keys
            return currentLeaves.filter { path ->
                val key = path.joinToString(".")
                key !in referenceKeys && pluralBaseOf(key, pluralSeparator) !in referencePlurals
            }
        }

        /** [key] without its CLDR plural suffix (`item` for `item_few`), or null when it carries none. */
        private fun pluralBaseOf(key: String, pluralSeparator: String): String? {
            val base = PluralKey.stripSuffix(key, pluralSeparator)
            return base.takeIf { it != key && key[it.length] == '_' }
        }
    }
}

/**
 * Deletes the orphan key from this locale, its separating comma with it: a bare
 * `JsonProperty.delete()` leaves `{,"b":…}` behind. An object emptied by the deletion stays, as
 * *Delete key with no reference found* leaves it.
 */
private class RemoveOrphanKeyFix(private val key: String) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.orphan.key.fix.remove.name", key)
    override fun getFamilyName(): String = PluginBundle.message("inspection.orphan.key.fix.remove.family")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val property = TranslationPsi.propertyOf(element) ?: return
        deletePropertyAndSeparator(property)
    }
}

/**
 * Adds the orphan key to the reference locale with an **empty** value, through the same path as
 * [MissingTranslationKeyInspection]'s fix and *Sync Keys*: spelled by [KeySpelling], turned into a
 * [com.ibrahimdans.i18n.plugin.key.FullKey] by [KeysSynchronizer.buildFullKey], saved by
 * [DialogViewModel.saveTranslation] — flat or nested as the project writes its keys.
 *
 * Empty rather than the locale's value: a French sentence in `en/common.json` looks like the
 * reference text; an empty one is what [EmptyTranslationValueInspection] points at.
 */
private class AddToReferenceFix(private val path: List<String>, private val referenceLocale: String) : LocalQuickFix {

    override fun getName(): String =
        PluginBundle.message("inspection.orphan.key.fix.add.name", path.joinToString("."), referenceLocale)
    override fun getFamilyName(): String = PluginBundle.message("inspection.orphan.key.fix.add.family")

    /** No preview: the fix writes another file than the one shown, which the preview cannot display. */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    // The write action is opened here: the command must be global, the file it writes not being
    // the one in the editor.
    override fun startInWriteAction(): Boolean = false

    /**
     * Writes the reference file in a command of its own, global and undone without a confirmation:
     * one Ctrl+Z in the locale's editor takes the key back out of the reference. That command cannot
     * nest in the platform's — a nested command keeps the outer one's confirmation policy — so it
     * runs right after it ends, as *Delete key with no reference found* does.
     *
     * The reference file is made writable first: one a VCS keeps read-only is checked out, or the
     * fix is dropped when the user refuses.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val source = TranslationFileScope.sourceOf(file) ?: return
        val reference = TranslationFileScope.counterpartOf(file, source, referenceLocale) ?: return
        val config = Settings.getInstance(project).config()
        val key = path.fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
        val fullKey = KeysSynchronizer().buildFullKey(key, config)
        ApplicationManager.getApplication().invokeLater({
            val referenceFile = reference.tree?.value()?.containingFile?.virtualFile ?: return@invokeLater
            if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(referenceFile)).hasReadonlyFiles()) {
                return@invokeLater
            }
            WriteCommandAction.writeCommandAction(project)
                .withName(getName())
                .withGlobalUndo()
                .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
                .run<RuntimeException> { DialogViewModel(project).saveTranslation(reference, fullKey, "") }
        }, project.disposed)
    }
}
