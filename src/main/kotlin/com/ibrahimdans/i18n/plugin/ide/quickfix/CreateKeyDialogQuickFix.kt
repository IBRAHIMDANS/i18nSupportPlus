package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * *Create i18n key* on an unresolved key in the editor: opens the translation dialog the tool
 * window's *Add translation* opens, with the key filled in and its namespace selected.
 *
 * The fix used to be [CreateKeyQuickFix]'s three steps — a bare *Translation Value* prompt
 * naming neither locale nor file, a popup picking the files, then a placeholder dialog for the
 * remaining locales — and an empty prompt wrote **the key itself as the value**, a string that
 * looked translated everywhere (`"saxve": "common:actions.saxve"`). The dialog shows one field
 * per locale with the file it writes to, checks the key as it is typed, refuses to write
 * nothing, and writes nothing on Cancel. [CreateKeyQuickFix] remains the extraction actions'
 * path, where the value is the extracted text.
 *
 * The dialog is scoped to the module of the file the key is written in, like the writes were.
 */
class CreateKeyDialogQuickFix(private val fullKey: FullKey, private val text: String) : QuickFix() {

    override fun getText(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor) {
        val caller = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        // A modal dialog cannot open from inside the intention's action; the next EDT turn can.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) opener(project, fullKey, caller)
        }
    }

    companion object {
        /**
         * Opens the dialog. A test swaps it for something headless — a `DialogWrapper` cannot
         * be shown in a test container — and drives the same [com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel]
         * writes the dialog's OK runs.
         */
        internal var opener: (Project, FullKey, PsiFile?) -> Unit = { project, key, caller ->
            TranslationDialog(project, key, Mode.CREATE, caller).show()
        }
    }
}
