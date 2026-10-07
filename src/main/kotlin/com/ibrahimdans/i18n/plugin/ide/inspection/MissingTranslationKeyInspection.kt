package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Flags, in a locale file, every key of the reference locale's file for the same namespace that
 * this file lacks — `en/common.json` holds `user.name`, `fr/common.json` does not.
 *
 * It is the most common translation defect, and until now only the tool window's Stats tab showed
 * it. The reference file itself is never inspected: [TranslationFileKeys.referenceFileOf] finds no
 * counterpart for it, so the reference locale defines what "complete" means and is never told it
 * falls short of itself.
 *
 * The problem is anchored on the closest object of the key's path that does exist — the name of
 * `user` for a missing `user.name`, the opening of the root object when nothing of the path is
 * there — since the missing key has no element of its own to point at.
 *
 * Plural forms are compared as a group ([PluralKey.groupForms]), as *Sync Keys* does: `ja` holding
 * only `item_other` has the plural that `en` spells `item_one` / `item_other`.
 */
class MissingTranslationKeyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nMissingKey"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is JsonFile || element is YAMLFile) checkFile(element as PsiFile, holder)
            }
        }
    }

    private fun checkFile(file: PsiFile, holder: ProblemsHolder) {
        val reference = TranslationFileKeys.referenceFileOf(file) ?: return
        val referenceLeaves = TranslationFileKeys.translationLeaves(reference).keys
        if (referenceLeaves.isEmpty()) return
        val root = rootOf(file) ?: return
        val source = TranslationFileScope.sourceOf(file) ?: return
        val referenceLocale = TranslationFileScope.referenceLocaleFor(file, source)

        for (path in missingPaths(referenceLeaves, TranslationFileKeys.translationLeaves(file).keys)) {
            val anchor = anchorOf(root, path) ?: continue
            val key = path.joinToString(".")
            val message = PluginBundle.message("inspection.missing.key.message", key, referenceLocale)
            if (anchor.canHoldKey) {
                holder.registerProblem(anchor.element, message, AddMissingKeyFix(path))
            } else {
                // The path runs into a leaf (`user` is a string here, an object in the reference):
                // no key can be added under it without overwriting a translation.
                holder.registerProblem(anchor.element, message)
            }
        }
    }

    /** Where a missing key is reported, and whether a key can be inserted there. */
    private class Anchor(val element: PsiElement, val canHoldKey: Boolean)

    /**
     * Walks [path] down from [root] and stops at the deepest level that exists, or returns null
     * when the whole path exists — as a non-string value the leaf scan skipped, for instance.
     */
    private fun anchorOf(root: PsiElement, path: List<String>): Anchor? {
        var container: PsiElement = root
        var anchor = Anchor(rootAnchorOf(root), canHoldKey = true)
        for ((index, segment) in path.withIndex()) {
            val property = childOf(container, segment) ?: return anchor
            if (index == path.lastIndex) return null
            val value = property.value
            val keyElement = property.key ?: return anchor
            if (value !is JsonObject && value !is YAMLMapping) return Anchor(keyElement, canHoldKey = false)
            container = value
            anchor = Anchor(keyElement, canHoldKey = true)
        }
        return anchor
    }

    /** A property of a JSON object or a YAML mapping, seen the same way. */
    private class Property(val key: PsiElement?, val value: PsiElement?)

    private fun childOf(container: PsiElement, name: String): Property? = when (container) {
        is JsonObject -> container.findProperty(name)?.let { Property(it.nameElement, it.value) }
        is YAMLMapping -> container.getKeyValueByKey(name)?.let { Property(it.key, it.value) }
        else -> null
    }

    /** The top-level object of [file]: what a key missing from the root is reported on. */
    private fun rootOf(file: PsiFile): PsiElement? = when (file) {
        is JsonFile -> file.topLevelValue as? JsonObject
        // The first document only, as TranslationFileKeys reads the keys and resolution finds them.
        is YAMLFile -> file.documents.firstOrNull()?.topLevelValue as? YAMLMapping
        else -> null
    }

    /**
     * The opening `{` of a JSON root, the first token of a YAML one: reporting on the whole root
     * would underline the entire file once per missing key.
     */
    private fun rootAnchorOf(root: PsiElement): PsiElement =
        if (root is JsonObject) root.firstChild ?: root else PsiTreeUtil.getDeepestFirst(root)

    internal companion object {

        /**
         * The paths of [referenceLeaves] that [currentLeaves] lacks, plural groups compared as a
         * whole: a group absent from the current file is reported once, by its `_other` form —
         * the one form every language has, and the one *Sync Keys* would create.
         */
        fun missingPaths(referenceLeaves: Collection<List<String>>, currentLeaves: Collection<List<String>>): List<List<String>> {
            val referenceByKey = referenceLeaves.associateBy { it.joinToString(".") }
            val currentKeys = currentLeaves.mapTo(HashSet()) { it.joinToString(".") }
            val currentGroups = PluralKey.groupForms(currentKeys).keys

            return PluralKey.groupForms(referenceByKey.keys).mapNotNull { (base, forms) ->
                if (base in currentGroups || forms.any { it in currentKeys }) return@mapNotNull null
                if (forms.size == 1 && forms.single() == base) return@mapNotNull referenceByKey.getValue(base)
                val form = forms.first()
                val formPath = referenceByKey.getValue(form)
                val suffixLength = form.length - base.length
                formPath.dropLast(1) + PluralKey.defaultForm(formPath.last().dropLast(suffixLength))
            }
        }
    }
}

/**
 * Adds the missing key to this file with an **empty** value.
 *
 * Empty rather than the reference value, as *Sync Keys* does: a copied English sentence in
 * `fr/common.json` looks translated and ships as such, while an empty value is what
 * [EmptyTranslationValueInspection] exists to point at until someone translates it.
 *
 * The write goes through the same path as [KeysSynchronizer]: the key is spelled the way the
 * synchronizer spells it ([KeySpelling]), turned into a [com.ibrahimdans.i18n.plugin.key.FullKey]
 * by [KeysSynchronizer.buildFullKey] and saved by [DialogViewModel.saveTranslation], so the quick
 * fix and *Sync Keys* create the same thing in the same place, flat or nested.
 */
private class AddMissingKeyFix(private val path: List<String>) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.missing.key.fix.name", path.joinToString("."))
    override fun getFamilyName(): String = PluginBundle.message("inspection.missing.key.fix.family")

    // startInWriteAction() keeps its default: outside the platform's write action the file in
    // the editor is read-only to a quick fix, and saveTranslation's own command nests inside it.

    /**
     * No preview: the fix writes through the localization source, i.e. the physical file, which
     * the platform's preview — applying the fix to a copy — must never touch.
     */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val source = TranslationFileScope.sourceOf(file) ?: return
        val config = Settings.getInstance(project).config()
        val key = path.fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
        DialogViewModel(project).saveTranslation(source, KeysSynchronizer().buildFullKey(key, config), "")
    }
}
