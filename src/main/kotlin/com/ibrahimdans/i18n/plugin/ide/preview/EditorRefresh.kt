package com.ibrahimdans.i18n.plugin.ide.preview

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/**
 * Makes the open editors show a settings change.
 *
 * `DaemonCodeAnalyzer.restart()` alone is not enough: the declarative inlay pass and the
 * folding pass both cache their result on the PSI and document modification stamps, and a
 * settings change moves neither — so switching the preview locale changed nothing on screen
 * until the next keystroke. Dropping the PSI caches bumps the stamp both passes watch, the
 * inlay pass is rescheduled per editor besides, and the daemon then runs both again.
 *
 * `scheduleRecompute` is the public entry point of the inlay factory; the no-arg
 * `resetModificationStamp()` it wraps is `@ApiStatus.Internal` and is rejected by the
 * Marketplace verifier.
 */
object EditorRefresh {

    /**
     * Re-runs folding, inlay hints and annotation in every open editor of [project]. Runs on the
     * EDT; [then] runs once the PSI caches are dropped, so that what it computes reads the change.
     */
    fun afterSettingsChange(project: Project, then: () -> Unit = {}) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WriteAction.run<RuntimeException> { PsiManager.getInstance(project).dropPsiCaches() }
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { DeclarativeInlayHintsPassFactory.scheduleRecompute(it, project) }
            DaemonCodeAnalyzer.getInstance(project).restart()
            then()
        }
    }

    /**
     * Re-runs folding, inlay hints and annotation in every open editor of [project] after a change
     * that only moves what they display — the preview locale — and none of what the plugin caches
     * on the PSI: unlike [afterSettingsChange], the PSI caches stay. Dropping them made the pass
     * after a locale switch 1.35 s on the benchmark's component, against 0.6 s after a keystroke:
     * every source lookup and parsed tree was rebuilt for a change that touched none of them.
     * Each editor's folding update and inlay pass are rescheduled instead, since both cache on
     * stamps the switch does not move. [then] runs on the EDT once they are.
     */
    fun afterDisplayChange(project: Project, then: () -> Unit = {}) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val folding = CodeFoldingManager.getInstance(project)
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { editor ->
                    folding.scheduleAsyncFoldingUpdate(editor)
                    DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
                }
            DaemonCodeAnalyzer.getInstance(project).restart()
            then()
        }
    }
}
