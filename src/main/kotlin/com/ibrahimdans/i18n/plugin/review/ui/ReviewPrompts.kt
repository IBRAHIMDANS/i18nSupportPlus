package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * What a review decision asks or tells around [ReviewActions], wherever it is made from — the
 * table's menu and the gutter of a translation file — so that both ask the same question and
 * report a failed write alike.
 */
object ReviewPrompts {

    /** The comment a change request carries, asked for; null when cancelled. An empty one cannot be confirmed. */
    fun askChangeRequest(project: Project): String? =
        Messages.showMultilineInputDialog(
            project,
            PluginBundle.message("review.request.changes.prompt"),
            PluginBundle.message("review.request.changes.title"),
            "",
            null,
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
                override fun canClose(inputString: String?): Boolean = checkInput(inputString)
            },
        )

    /** An error naming the locales of [cells] when nothing could be [written]: a review file holding a merge conflict. */
    fun reportIfUnwritten(project: Project, written: Boolean, cells: List<ReviewCell>) {
        if (written) return
        Messages.showErrorDialog(
            project,
            PluginBundle.message("review.write.failed", cells.map { it.locale }.distinct().joinToString(", ")),
            PluginBundle.message("review.write.failed.title")
        )
    }
}
