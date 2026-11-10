package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.plugin.ide.actions.EditTranslationAtCaretAction
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * *Create missing translations* on a key some checked locales hold and others lack: opens the
 * translation dialog on the key, as *Edit Translation* does from the caret. The locales holding
 * it show their value, the others are left to fill; OK writes every locale in one command, so one
 * Ctrl+Z undoes it, and Cancel writes nothing.
 *
 * The fix used to write **the key itself** as the value into each locale lacking it
 * (`"base": "test:root.sub.base"`) — a string that looked translated — in one command per file,
 * from the references captured when the key was annotated, without asking for the files to be
 * writable. The dialog now reads the files as they are when it opens; [references] only name the
 * files to make writable first, so a locked one stops the fix before anything is written.
 */
class CreateMissingKeysQuickFix(
    // The annotator's call passes the key; the dialog reads it again from the code at the caret,
    // with its namespace and its plural form resolved as *Edit Translation* resolves them.
    @Suppress("unused", "UnusedPrivateProperty") private val fullKey: FullKey,
    private val references: List<PropertyReference<PsiElement>>,
    private val commandCaption: String,
) : QuickFix() {

    override fun getText(): String = commandCaption

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor) {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val offset = editor.caretModel.offset
        // A modal dialog cannot open from inside the intention's action; the next EDT turn can.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val key = ReadAction.compute<PsiElement?, RuntimeException> {
                // The caret right after the closing quote is still on the key for the user.
                if (file.isValid) file.findElementAt(offset) ?: file.findElementAt(offset - 1) else null
            } ?: return@invokeLater
            if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(translationFiles()).hasReadonlyFiles()) return@invokeLater
            EditTranslationAtCaretAction.edit(project, key)
        }
    }

    /** The translation files of the checked locales, those the dialog writes; a stale reference names none. */
    private fun translationFiles(): List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
        references.mapNotNull { ref ->
            val psi = ref.element?.value() ?: ref.localizationSource.tree?.value() ?: ref.localizationSource.host
            psi?.takeIf { it.isValid }?.hostVirtualFile()
        }.distinct()
    }
}
