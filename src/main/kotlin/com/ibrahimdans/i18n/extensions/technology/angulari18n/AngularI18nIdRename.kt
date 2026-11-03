package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularI18nMetadata
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffDocument
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffTree
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewMove
import com.ibrahimdans.i18n.plugin.review.ReviewSpot
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import java.util.Collections

/**
 * *Rename* (Shift+F6) of an Angular message id, from a template's marker (`i18n="@@home.title"`), a
 * `` $localize`:@@home.title:…` `` or a unit's `id` in an XLIFF file: every marker naming the id and its
 * unit in every XLIFF file `angular.json` lists are renamed, and the id's reviews follow it in each
 * locale, in one undo.
 *
 * The id is one key, so the whole id is asked for, dots included — where an i18next key renames its last
 * segment. A new id another unit already has is refused: two messages would merge.
 */
internal object AngularI18nIdRename {

    private const val MARKER = "@@"

    /** The id under the caret of [editor], or null when the caret is on no Angular marker nor XLIFF unit id. */
    fun idAt(editor: Editor, file: PsiFile): String? {
        val offset = editor.caretModel.offset
        // An inline `template: '…'` is a fragment injected into the component's string.
        val leaf = InjectedLanguageManager.getInstance(file.project).findInjectedElementAt(file, offset) ?: file.findElementAt(offset)
        return leaf?.let(::idAround)
    }

    /** Asks for [id]'s new name, then renames it everywhere. */
    fun rename(project: Project, id: String) {
        val title = PluginBundle.message("action.rename.title")
        val newId = Messages.showInputDialog(project, PluginBundle.message("action.rename.key.prompt", id), title, Messages.getQuestionIcon(), id, null)
            ?.trim() ?: return
        if (newId == id || AngularI18nMetadata.idOf("$MARKER$newId") != newId) return
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<List<RenameI18nKeyHandler.Edit>, List<ReviewMove>>?, RuntimeException>(
            {
                ReadAction.compute<Pair<List<RenameI18nKeyHandler.Edit>, List<ReviewMove>>?, RuntimeException> {
                    editsOf(project, id, newId)?.let { it to reviewMovesOf(project, id, newId) }
                }
            },
            title,
            true,
            project,
        ) ?: return Messages.showErrorDialog(project, PluginBundle.message("action.rename.angular.conflict", newId), title)
        RenameI18nKeyHandler().apply(project, plan.first, title, plan.second)
    }

    /**
     * The moves of [id]'s reviews to [newId], one per XLIFF file whose module keeps reviews. The id is
     * spelled as the table files its review — without a default namespace, then read back — since
     * `ReviewMigration.moveOf` reads the keys of JSON and YAML files, not an XLIFF unit's id.
     */
    internal fun reviewMovesOf(project: Project, id: String, newId: String): List<ReviewMove> {
        val config = Settings.getInstance(project).config()
        val defaults = config.defaultNamespaces()
        val store = ReviewStore.getInstance(project)
        return xliffFiles(project).mapNotNull { file ->
            val source = TranslationFileScope.sourceOf(file) ?: return@mapNotNull null
            val module = ModuleSources.owner(config.modules, TranslationDataLoader.projectPathOf(source))
            val root = ReviewLocation.rootOf(project, module)?.takeIf { store.isEnabled(it) } ?: return@mapNotNull null
            val namespace = TranslationDataLoader.extractNamespace(source, defaults.first())
            fun keyOf(path: String) = ReviewKey.fromSpelling(ReviewKey(namespace, path).spelling(defaults), defaults.first())
            // An id is flat: renaming `home` leaves the review of `home.title`, another unit, where it is.
            ReviewMove(ReviewSpot(root, source.localeLabel(), keyOf(id)), keyOf(newId), flat = true)
        }.distinct()
    }

    /** The edits renaming [id] to [newId], or null when a unit is already called [newId]. */
    internal fun editsOf(project: Project, id: String, newId: String): List<RenameI18nKeyHandler.Edit>? {
        val files = xliffFiles(project)
        if (files.any { newId in XliffDocument.units(it) }) return null
        val unitEdits = files.mapNotNull { file ->
            val value = XliffDocument.units(file)[id]?.idValue ?: return@mapNotNull null
            edit(value, value.valueTextRange, StringUtil.escapeXmlEntities(newId))
        }
        val markerEdits = markers(project, id).mapNotNull { (marker, range) -> edit(marker, range, newId) }
        return (unitEdits + markerEdits).distinct()
    }

    /** The id [element] or one of its two parents names: a code marker's, or a unit's `id` in an XLIFF file. */
    private fun idAround(element: PsiElement): String? =
        generateSequence(element) { it.parent }.take(3).firstNotNullOfOrNull { AngularI18nMetadata.keyOf(it) ?: unitIdOf(it) }

    private fun unitIdOf(element: PsiElement): String? {
        val value = element as? XmlAttributeValue ?: return null
        val attribute = value.parent as? XmlAttribute ?: return null
        val tag = attribute.parent ?: return null
        if (attribute.name != "id" || !XliffDocument.isUnit(tag) || XliffDocument.rootOf(value.containingFile) == null) return null
        return value.value.trim().takeIf { it.isNotEmpty() }
    }

    /** The XLIFF files that are translation sources: those `angular.json` lists. */
    private fun xliffFiles(project: Project): List<PsiFile> =
        project.service<LocalizationSourceService>().findAllSources(project)
            .mapNotNull { (it.tree as? XliffTree)?.value() as? PsiFile }
            .distinct()

    /**
     * The markers naming [id] — template attributes and `$localize` templates, inline templates
     * included — each with the range of the id in its file. Found by a word of the id, then kept when the
     * marker names the very id: `home.title` is no `home.titles`.
     */
    private fun markers(project: Project, id: String): List<Pair<PsiElement, TextRange>> {
        val word = StringUtil.getWordsIn(id).maxByOrNull { it.length } ?: return emptyList()
        val found: MutableSet<Pair<PsiElement, TextRange>> = Collections.synchronizedSet(linkedSetOf())
        val injections = InjectedLanguageManager.getInstance(project)
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, offset ->
                val injected = injections.findInjectedElementAt(element.containingFile, element.textRange.startOffset + offset)
                listOfNotNull(element, injected).forEach { leaf ->
                    generateSequence(leaf) { it.parent }.take(3).firstOrNull { AngularI18nMetadata.keyOf(it) == id }?.let { marker ->
                        val start = marker.text.indexOf(MARKER + id)
                        if (start >= 0) found += marker to TextRange.from(marker.textRange.startOffset + start + MARKER.length, id.length)
                    }
                }
                true
            },
            GlobalSearchScope.projectScope(project),
            word,
            UsageSearchContext.ANY,
            true,
        )
        return found.toList()
    }

    /** The edit writing [text] over [range] of [element]'s file, in its host document. */
    private fun edit(element: PsiElement, range: TextRange, text: String): RenameI18nKeyHandler.Edit? {
        val injections = InjectedLanguageManager.getInstance(element.project)
        val hostRange = injections.injectedToHost(element, range)
        val hostFile = injections.getTopLevelFile(element) ?: return null
        val document = PsiDocumentManager.getInstance(element.project).getDocument(hostFile) ?: return null
        return RenameI18nKeyHandler.Edit(document, hostRange, text)
    }
}
