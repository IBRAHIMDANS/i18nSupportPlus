package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewMove
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameHandler
import java.util.Collections

/**
 * Handles Shift+F6 rename refactoring for i18n keys.
 *
 * When the cursor is on an i18n key literal (e.g. 'common:button.save'), this handler renames the
 * last key segment everywhere the key lives:
 *  1. every code literal naming this very key — the one under the caret and all the others,
 *     however it is written (`common:button.save`, `button.save` under a hook namespace, `save`
 *     under a key prefix);
 *  2. the matching property in every translation file, JSON and YAML, plural forms included
 *     (`item_one` / `item_other` follow `item`).
 *
 * It used to rewrite the caret's literal and the JSON properties only: every other call site kept
 * the old name and broke, YAML files and plural forms were left behind, and `.` was hard-coded.
 *
 * With the caret on an intermediate segment of the key (`button` in `common:button.save`), or on a
 * property holding other keys in a translation file, the whole level is renamed instead: see
 * [KeyLevelRename].
 *
 * The reviews of the renamed keys ([ReviewMigration]) follow them in the same command, in every
 * locale and module that has reviews on: the values do not change, so an approval still holds.
 */
class RenameI18nKeyHandler : RenameHandler {

    /** One text replacement, gathered under a read action and applied in the write action. */
    private data class Edit(val document: Document, val range: TextRange, val text: String)

    /** What a rename writes: the text [edits], and the [reviews] following the renamed keys. */
    private data class Collected(val edits: List<Edit>, val reviews: List<ReviewMove>)

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        return findI18nReference(editor, psiFile) != null || paraglideMessage(editor, psiFile) != null ||
            levelInTranslationFile(editor, psiFile) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        // Paraglide's `m.hello` holds no I18nReference: its key is renamed as the leaf it names.
        paraglideMessage(editor, file)?.let { return renameLevel(project, it, leaf = true) }
        val config = Settings.getInstance(project).config()
        val ref = findI18nReference(editor, file)
        val level = if (ref != null) levelInCode(ref, editor, config) else levelInTranslationFile(editor, file)
        if (level != null) return renameLevel(project, level)
        if (ref == null) return

        val currentKey = ref.element.text.unQuote()
        val lastSegment = lastSegmentOf(currentKey, config)

        val newSegment = Messages.showInputDialog(
            project,
            PluginBundle.message("action.rename.prompt", currentKey),
            PluginBundle.message("action.rename.title"),
            Messages.getQuestionIcon(),
            lastSegment,
            null
        ) ?: return // cancelled

        if (newSegment.isBlank() || newSegment == lastSegment) return

        // The search walks the project's sources: off the EDT, under a progress the user can cancel.
        val collected = ProgressManager.getInstance().runProcessWithProgressSynchronously<Collected, RuntimeException>(
            { ReadAction.compute<Collected, RuntimeException> { collectEdits(project, ref, lastSegment, newSegment, config) } },
            PluginBundle.message("action.rename.title"),
            true,
            project
        )

        apply(project, collected.edits, PluginBundle.message("action.rename.title"), collected.reviews)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        // Not used — rename is always initiated from the editor
    }

    // --- Level rename ---

    /**
     * The level under the caret when it sits on an intermediate segment of [ref]'s key — not its
     * namespace, not its last segment, which the plain rename handles.
     */
    private fun levelInCode(ref: I18nReference, editor: Editor, config: Config): KeyLevelRename? {
        if (config.usesFlatKeys() || config.keySeparator.isEmpty()) return null
        val literal = ref.element
        val project = literal.project
        val fullKey = ReadAction.compute<FullKey?, RuntimeException> {
            Extensions.LANG.extensionList.firstNotNullOfOrNull { it.extractRawKey(literal) }?.let { RawKeyParser(project).parse(it, literal) }
        } ?: return null
        val text = literal.text
        val quoted = text.length > 1 && text.first() in QUOTES && text.last() == text.first()
        val key = if (quoted) text.substring(1, text.length - 1) else text
        val nsPart = if (fullKey.ns != null && config.nsSeparator.isNotEmpty() && key.contains(config.nsSeparator)) key.substringBefore(config.nsSeparator) + config.nsSeparator else ""
        val caret = editor.caretModel.offset - literal.textRange.startOffset - (if (quoted) 1 else 0) - nsPart.length
        if (caret < 0) return null
        val segments = key.removePrefix(nsPart).split(config.keySeparator)
        var start = 0
        val writtenIndex = segments.indexOfFirst { segment ->
            val end = start + segment.length
            (caret in start..end).also { start = end + config.keySeparator.length }
        }
        if (writtenIndex < 0 || writtenIndex == segments.size - 1) return null
        val path = fullKey.keyPrefix.map { it.text } + segments.take(writtenIndex + 1)
        val namespaces = fullKey.allNamespaces().ifEmpty { config.defaultNamespaces() }
        return KeyLevelRename(project, namespaces, path, config, literal)
    }

    /** The level under the caret in a translation file: a property holding other keys, not a value. */
    private fun levelInTranslationFile(editor: Editor, file: PsiFile): KeyLevelRename? {
        TranslationFileScope.sourceOf(file) ?: return null
        val element = file.findElementAt(editor.caretModel.offset) ?: return null
        val property = TranslationPsi.propertyOf(element, strict = false) ?: return null
        val entry = TranslationPsi.entryOf(property) ?: return null
        if (!PsiTreeUtil.isAncestor(entry.keyElement, element, false)) return null
        // A leaf is left to the platform's rename, but in a Paraglide project, whose `m.hello` calls must follow.
        if (entry.literal != null && !KeyLevelRename.isParaglideProject(file)) return null
        return KeyLevelRename.inTranslationFile(property)
    }

    /** The Paraglide message the caret is on — `m.hello`, `m["a.b"]` — as the leaf to rename, or null. */
    private fun paraglideMessage(editor: Editor, file: PsiFile): KeyLevelRename? =
        file.findElementAt(editor.caretModel.offset)?.let(KeyLevelRename::ofParaglideMember)

    /**
     * Asks for the new name of [level], [suggested] filled in, then renames it everywhere once
     * confirmed. A [leaf] — a key holding a value — is asked for as a key, not as a level.
     */
    internal fun renameLevel(project: Project, level: KeyLevelRename, suggested: String = level.levelName, leaf: Boolean = false) {
        val title = PluginBundle.message(if (leaf) "action.rename.title" else "action.rename.level.title")
        val prompt = if (leaf) PluginBundle.message("action.rename.key.prompt", level.path.joinToString("."))
            else PluginBundle.message("action.rename.level.prompt", level.path.joinToString("."))
        val newName = Messages.showInputDialog(
            project,
            prompt,
            title,
            Messages.getQuestionIcon(),
            suggested,
            null
        )?.trim() ?: return
        if (newName.isEmpty() || newName == level.levelName) return

        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<KeyLevelRename.Plan, RuntimeException>(
            { ReadAction.compute<KeyLevelRename.Plan, RuntimeException> { level.plan(newName) } },
            title,
            true,
            project
        )
        when {
            plan.conflict -> return Messages.showErrorDialog(project, PluginBundle.message("action.rename.level.conflict", newName, level.levelName), title)
            plan.files == 0 -> return Messages.showInfoMessage(project, PluginBundle.message("action.rename.level.notFound", level.path.joinToString(".")), title)
        }
        val dynamic = if (plan.dynamicUsages.isEmpty()) ""
            else "\n\n" + PluginBundle.message("action.rename.level.dynamic", plan.dynamicUsages.joinToString("\n") { "  $it" })
        val confirmed = Messages.showYesNoDialog(
            project,
            PluginBundle.message("action.rename.level.confirm", level.levelName, newName, plan.files, plan.callSites) + dynamic,
            title,
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!confirmed) return
        apply(project, plan.edits.map { Edit(it.document, it.range, it.text) }, title, plan.reviews)
    }

    /**
     * Applies [edits] and moves [reviews] in one command. Global, without confirmation, as every
     * command writing reviews: the review file has no editor, and one Ctrl+Z must take both back.
     *
     * The files are made writable first — a file a VCS such as Perforce keeps read-only is checked
     * out, or the rename is dropped when the user refuses — rather than failing halfway through.
     */
    private fun apply(project: Project, edits: List<Edit>, command: String, reviews: List<ReviewMove>) {
        val files = edits.mapNotNull { FileDocumentManager.getInstance().getFile(it.document) }.distinct()
        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files).hasReadonlyFiles()) return
        WriteCommandAction.writeCommandAction(project)
            .withName(command)
            .withGlobalUndo()
            .withUndoConfirmationPolicy(UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION)
            .run<RuntimeException> {
                // From the end of each document, so an edit never shifts the ranges still to apply.
                edits.groupBy { it.document }.forEach { (document, documentEdits) ->
                    documentEdits.sortedByDescending { it.range.startOffset }.forEach {
                        document.replaceString(it.range.startOffset, it.range.endOffset, it.text)
                    }
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
                ReviewMigration.move(project, reviews)
            }
    }

    // --- Collection (read action) ---

    private fun collectEdits(
        project: Project,
        ref: I18nReference,
        lastSegment: String,
        newSegment: String,
        config: Config,
    ): Collected {
        val translations = ref.multiResolve(false).mapNotNull { it.element }.toSet()
        val codeEdits = codeUsages(project, ref, translations, lastSegment, config).mapNotNull { literal ->
            renamedLiteral(literal, lastSegment, newSegment, config)
        }
        val translationEdits = translations.mapNotNull { renamedProperty(it, lastSegment, newSegment, config) }
        // Each translation moves its own review, plural forms included: `item_one` → `product_one`.
        val reviews = translations.mapNotNull { translation ->
            ReviewMigration.moveOf(translation) { path ->
                val renamed = path.lastOrNull()?.let { renamedName(it, lastSegment, newSegment, config) }
                if (renamed == null) path else path.dropLast(1) + renamed
            }
        }
        return Collected((codeEdits + translationEdits).distinct(), reviews.distinct())
    }

    /**
     * The code literals whose reference resolves to the very translations [ref] resolves to.
     *
     * Found by searching the last segment as a word — every way of writing the key contains it —
     * then kept only when the literal's own reference lands on the same translation elements. That
     * check is what keeps `other:button.save`, a different key sharing the word, out of the rename.
     *
     * The search hands files to several threads at once, so the processor runs concurrently: the
     * usages go into a synchronized set, which a plain one cannot survive (adds racing a resize
     * corrupt it, and reading it back then throws at random). Its order no longer means anything,
     * which is fine: the edits are applied per document, from the end of the file backwards.
     */
    private fun codeUsages(
        project: Project,
        ref: I18nReference,
        translations: Set<PsiElement>,
        lastSegment: String,
        config: Config,
    ): List<PsiElement> {
        val usages: MutableSet<PsiElement> = Collections.synchronizedSet(linkedSetOf(ref.element))
        val languages = Extensions.LANG.extensionList
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
                val reference = listOfNotNull(literal, literal?.parent)
                    .firstNotNullOfOrNull { candidate -> candidate.references.filterIsInstance<I18nReference>().firstOrNull() }
                if (reference != null && reference.multiResolve(false).any { it.element in translations }) {
                    usages += reference.element
                }
                true
            },
            config.searchScope(project),
            lastSegment,
            UsageSearchContext.ANY,
            true
        )
        return usages.toList()
    }

    /** The edit renaming [literal]'s last segment, or null when it does not end with [lastSegment]. */
    private fun renamedLiteral(literal: PsiElement, lastSegment: String, newSegment: String, config: Config): Edit? {
        val document = documentOf(literal) ?: return null
        val original = literal.text
        val quoted = original.length > 1 && original.first() in QUOTES && original.last() == original.first()
        val key = if (quoted) original.substring(1, original.length - 1) else original
        val renamed = renameLastSegment(key, lastSegment, newSegment, config) ?: return null
        val text = if (quoted) "${original.first()}$renamed${original.last()}" else renamed
        return Edit(document, literal.textRange, text)
    }

    /**
     * The edit renaming the property holding [translation] (a value node), plural suffix kept, or
     * null when that property is not named after [lastSegment].
     */
    private fun renamedProperty(translation: PsiElement, lastSegment: String, newSegment: String, config: Config): Edit? {
        val nameElement = TranslationPsi.keyElementOf(translation) ?: return null
        val document = documentOf(nameElement) ?: return null
        val raw = nameElement.text
        val quote = raw.firstOrNull()?.takeIf { it in QUOTES && raw.length > 1 && raw.last() == it }
        val renamed = renamedName(raw.unQuote(), lastSegment, newSegment, config) ?: return null
        val text = if (quote != null) "$quote$renamed$quote" else renamed
        return Edit(document, nameElement.textRange, text)
    }

    // --- Helpers ---

    /** The property [name] renamed from [lastSegment] to [newSegment], plural suffix kept, or null when it is not named after it. */
    private fun renamedName(name: String, lastSegment: String, newSegment: String, config: Config): String? = when {
        name == lastSegment -> newSegment
        PluralKey.stripSuffix(name, config.pluralSeparator) == lastSegment -> newSegment + name.removePrefix(lastSegment)
        else -> null
    }

    /**
     * [key] with its last segment [lastSegment] replaced by [newSegment], or null when the key does
     * not end with that segment. The segment must stand whole: after the key separator, after the
     * namespace separator, or alone (a key written under a hook's key prefix).
     */
    private fun renameLastSegment(key: String, lastSegment: String, newSegment: String, config: Config): String? {
        if (key == lastSegment) return newSegment
        val boundaries = listOfNotNull(
            config.keySeparator.takeUnless { config.usesFlatKeys() || it.isEmpty() },
            config.nsSeparator.takeIf { it.isNotEmpty() }
        )
        return boundaries.firstNotNullOfOrNull { separator ->
            if (key.endsWith(separator + lastSegment)) key.dropLast(lastSegment.length) + newSegment else null
        }
    }

    /** The segment a rename replaces: the key path's last level, or the whole path for flat keys. */
    private fun lastSegmentOf(key: String, config: Config): String {
        val path = if (config.nsSeparator.isNotEmpty() && key.contains(config.nsSeparator)) key.substringAfter(config.nsSeparator) else key
        if (config.usesFlatKeys() || config.keySeparator.isEmpty()) return path
        return path.substringAfterLast(config.keySeparator)
    }

    private fun documentOf(element: PsiElement): Document? =
        element.containingFile?.let { PsiDocumentManager.getInstance(element.project).getDocument(it) }

    /**
     * Finds the I18nReference on the PSI element at the caret position, if any.
     */
    private fun findI18nReference(editor: Editor, psiFile: PsiFile): I18nReference? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        // The reference is on the parent literal element
        val literal = element.parent ?: element
        return literal.references.filterIsInstance<I18nReference>().firstOrNull()
            ?: element.references.filterIsInstance<I18nReference>().firstOrNull()
    }

    private companion object {
        val QUOTES = setOf('"', '\'', '`')
    }
}
