package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewMove
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.psi.PsiDocumentManager

/**
 * Text edits of a key rename, applied with the reviews they move: shared by the rename handler,
 * the Angular `@@id` rename and the AI agents' tools.
 */
object KeyEdits {

    /** [text] in place of [range] of [document]. */
    data class Edit(val document: Document, val range: TextRange, val text: String)

    /**
     * Applies [edits] and moves [reviews] in one command. Global, without confirmation, as every
     * command writing reviews: the review file has no editor, and one Ctrl+Z must take both back.
     *
     * The files are made writable first — a file a VCS such as Perforce keeps read-only is checked
     * out, or the rename is dropped when the user refuses — rather than failing halfway through.
     */
    fun apply(project: Project, edits: List<Edit>, command: String, reviews: List<ReviewMove>) {
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
}
