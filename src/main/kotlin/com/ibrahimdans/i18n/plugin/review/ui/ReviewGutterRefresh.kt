package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project

/**
 * Re-runs the highlighting of the open editors when a review file changes — a decision made from
 * the gutter, the table or the review dialog, or a pull bringing a teammate's: a review lives in its
 * own file, so the edit of a translation file's reviews leaves that file's markers
 * ([ReviewLineMarkerProvider]) and the stale translations inspection as they were until the next keystroke.
 */
class ReviewGutterRefresh(private val project: Project) : ReviewStore.Listener {
    override fun reviewsChanged() {
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
