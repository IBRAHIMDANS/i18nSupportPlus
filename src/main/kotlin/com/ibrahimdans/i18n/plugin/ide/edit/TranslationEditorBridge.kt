package com.ibrahimdans.i18n.plugin.ide.edit

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.edit.TextEdit
import com.ibrahimdans.i18n.plugin.edit.TranslationEditor
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Applies the portable [TranslationEditor] edits to IDE documents. Callers already own the write
 * command; an unsupported format or edit is deliberately left for their PSI fallback.
 */
object TranslationEditorBridge {

    /** Inserts a value and, when requested, sorts the resulting JSON text in the same command. */
    fun insert(
        file: PsiFile,
        editor: TranslationEditor,
        path: List<String>,
        value: String,
        order: Comparator<String>? = null,
        sort: Comparator<String>? = null,
    ): Boolean {
        val document = documentOf(file) ?: return false
        val edits = editor.insert(document.text, path, value, order)
        if (edits.isEmpty()) return false
        apply(document, edits)
        sort?.let { apply(document, editor.sort(document.text, it)) }
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        return true
    }

    /** Sorts [file] through [editor], returning false when the editor cannot read it. */
    fun sort(file: PsiFile, editor: TranslationEditor, order: Comparator<String>): Boolean {
        val document = documentOf(file) ?: return false
        val edits = editor.sort(document.text, order)
        if (edits.isEmpty()) return false
        apply(document, edits)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        return true
    }

    /** The core rename edit for [property], or null when the PSI path must stay in charge. */
    fun rename(property: PsiElement, newName: String): TextEdit? {
        val file = property.containingFile ?: return null
        val editor = editorOf(file) ?: return null
        val path = TranslationFileKeys.pathOf(property).takeIf { it.isNotEmpty() } ?: return null
        return editor.rename(documentOf(file)?.text ?: return null, path, newName).singleOrNull()
    }

    /**
     * Deletes the properties that the portable editor can handle and returns those its PSI caller
     * must delete itself. A file is planned before it changes, so a parse failure never leaves a
     * batch half converted to document edits.
     */
    fun delete(properties: Collection<PsiElement>): List<PsiElement> {
        val fallback = mutableListOf<PsiElement>()
        properties.filter { it.isValid }.groupBy { it.containingFile }.forEach { (file, inFile) ->
            if (file == null) {
                fallback += inFile
                return@forEach
            }
            val editor = editorOf(file)
            val document = documentOf(file)
            val paths = inFile.associateWith { TranslationFileKeys.pathOf(it) }
            if (editor == null || document == null || paths.values.any { it.isEmpty() }) {
                fallback += inFile
                return@forEach
            }
            val selected = paths.values.distinct().filterNot { path ->
                paths.values.any { other -> other.size < path.size && path.startsWith(other) }
            }
            if (!canDelete(editor, document.text, selected)) {
                fallback += inFile
                return@forEach
            }
            selected.forEach { path -> apply(document, editor.delete(document.text, path)) }
            PsiDocumentManager.getInstance(file.project).commitDocument(document)
        }
        return fallback
    }

    /**
     * [source] with its tree read again from its file: a text edit rebuilds the PSI, so a source a
     * batch keeps across writes would otherwise point at elements no longer in the file.
     */
    fun refreshed(source: LocalizationSource): LocalizationSource {
        val file = source.tree?.value()?.containingFile ?: return source
        val tree = source.localization.elementsTree(file) ?: return source
        return source.copy(tree = tree)
    }

    /** Applies non-overlapping edits from the end so their original offsets stay valid. */
    fun apply(document: Document, edits: List<TextEdit>) {
        edits.sortedByDescending { it.span.start }.forEach { edit ->
            document.replaceString(edit.span.start, edit.span.end, edit.newText)
        }
    }

    private fun canDelete(editor: TranslationEditor, source: String, paths: List<List<String>>): Boolean {
        var text = source
        for (path in paths) {
            val edits = editor.delete(text, path)
            if (edits.isEmpty()) return false
            text = TextEdit.apply(text, edits)
        }
        return true
    }

    private fun editorOf(file: PsiFile): TranslationEditor? =
        (TranslationFileScope.sourceOf(file)?.localization as? TranslationEditorProvider)?.translationEditor(file.project)

    private fun documentOf(file: PsiFile): Document? = PsiDocumentManager.getInstance(file.project).getDocument(file)

    private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix
}
