package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameHandler

/**
 * Handles Shift+F6 rename refactoring for i18n keys.
 *
 * When the cursor is on an i18n key literal (e.g. 'common:button.save'),
 * this handler renames the last key segment in:
 *  1. The source code literal under the cursor
 *  2. All matching translation files (JSON/YAML) via the resolved PSI references
 */
class RenameI18nKeyHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val psiFile = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        return findI18nReference(editor, psiFile) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        val ref = findI18nReference(editor, file) ?: return

        val currentKey = ref.element.text.unQuote()
        val lastSegment = currentKey.substringAfterLast('.')
        val keyPrefix = currentKey.substringBeforeLast('.', missingDelimiterValue = "")

        val newSegment = Messages.showInputDialog(
            project,
            "Rename i18n key segment (last part of '$currentKey')",
            "Rename i18n Key",
            Messages.getQuestionIcon(),
            lastSegment,
            null
        ) ?: return // cancelled

        if (newSegment.isBlank() || newSegment == lastSegment) return

        val newKey = if (keyPrefix.isEmpty()) newSegment else "$keyPrefix.$newSegment"

        // Collect all resolved PSI elements from translation files
        val resolvedElements = ref.multiResolve(false).mapNotNull { it.element }

        WriteCommandAction.runWriteCommandAction(project, "Rename i18n Key", null, {
            // Rename in source code: replace the literal text
            renameSourceElement(ref.element, currentKey, newKey)

            // Rename in translation files: rename the JsonProperty key
            resolvedElements.forEach { translationElement ->
                renameTranslationElement(translationElement, lastSegment, newSegment)
            }
        })
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        // Not used — rename is always initiated from the editor
    }

    // --- Helpers ---

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

    /**
     * Renames the key text inside the source code literal.
     * Replaces the full key text (preserving quotes).
     */
    private fun renameSourceElement(element: PsiElement, oldKey: String, newKey: String) {
        val doc = element.containingFile?.viewProvider?.document ?: return
        val originalText = element.text
        // Replace only the key part, preserving surrounding quotes
        val updatedText = if (originalText.isQuoted()) {
            val quote = originalText.first()
            "$quote$newKey$quote"
        } else {
            newKey
        }
        if (updatedText != originalText) {
            doc.replaceString(element.textRange.startOffset, element.textRange.endOffset, updatedText)
        }
    }

    /**
     * Renames the translation key in a translation file element.
     *
     * The resolved element from I18nReference is the VALUE node of a JsonProperty.
     * We navigate to the parent JsonProperty to rename its key.
     */
    private fun renameTranslationElement(element: PsiElement, oldSegment: String, newSegment: String) {
        // The element may be the value of a JsonProperty; get the property
        val property = PsiTreeUtil.getParentOfType(element, JsonProperty::class.java)
            ?: element.parent as? JsonProperty
            ?: return

        val nameElement = property.nameElement
        val currentName = nameElement.text.unQuote()
        if (currentName != oldSegment) return

        val doc = nameElement.containingFile?.viewProvider?.document ?: return
        val originalText = nameElement.text
        val updatedText = if (originalText.isQuoted()) {
            val quote = originalText.first()
            "$quote$newSegment$quote"
        } else {
            newSegment
        }
        if (updatedText != originalText) {
            doc.replaceString(nameElement.textRange.startOffset, nameElement.textRange.endOffset, updatedText)
        }
    }

    private fun String.isQuoted(): Boolean =
        length > 1 && (startsWith('"') && endsWith('"') || startsWith('\'') && endsWith('\'') || startsWith('`') && endsWith('`'))
}
