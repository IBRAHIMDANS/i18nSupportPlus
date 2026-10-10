package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.ide.diff.LocalTranslationChanges
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChanges
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.psi.PsiManager
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * The translations the working tree changes against a branch: everything a branch adds to its
 * base, committed or not — what *Show Translation Changes* no longer sees once it is committed.
 *
 * Declared in `gitConfig.xml` only, so nothing of Git4Idea is loaded without the Git plugin.
 */
object BranchTranslationChanges {

    /**
     * The branches to compare with, one name per branch however many repositories hold it, local
     * ones and remote ones (`origin/main`) alike.
     */
    fun branchNames(project: Project): List<String> =
        GitRepositoryManager.getInstance(project).repositories.flatMap(::namesOf).distinct().sorted()

    /**
     * The branch offered first: the main line a branch is usually compared with, before the
     * current branch's own upstream — which only holds what was not pushed yet.
     */
    fun defaultBase(names: List<String>, tracked: String?): String? =
        PREFERRED_BASES.firstOrNull { it in names } ?: tracked?.takeIf { it in names } ?: names.firstOrNull()

    /** The upstream of the current branch of the first repository, as [branchNames] spells it. */
    fun trackedBranch(project: Project): String? {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        return repository.currentBranch?.findTrackedBranch(repository)?.nameForLocalOperations
    }

    /**
     * The translation changes of the working tree against [branch], in every repository holding
     * it. Runs `git`: call it off the EDT and outside any read action, which it takes itself.
     */
    fun collect(project: Project, branch: String): TranslationChanges {
        val changes = GitRepositoryManager.getInstance(project).repositories
            .filter { branch in namesOf(it) }
            .flatMap { GitChangeUtils.getDiffWithWorkingTree(it, branch, false).orEmpty() }
        val ofTranslations = ReadAction.compute<List<Change>, RuntimeException> {
            changes.filter { isTranslation(project, it) }
        }
        // The base text comes from `git show`: read here, not under the read action that follows.
        val loaded = ofTranslations.map { Change(it.beforeRevision?.let(::loaded), it.afterRevision) }
        return ReadAction.compute<TranslationChanges, RuntimeException> { LocalTranslationChanges.collect(project, loaded) }
    }

    private val PREFERRED_BASES = listOf("origin/main", "origin/master", "origin/develop", "main", "master", "develop")

    private fun namesOf(repository: GitRepository): List<String> =
        repository.branches.localBranches.map { it.name } + repository.branches.remoteBranches.map { it.nameForLocalOperations }

    private fun isTranslation(project: Project, change: Change): Boolean {
        val file = change.afterRevision?.file?.virtualFile ?: return false
        val psi = PsiManager.getInstance(project).findFile(file) ?: return false
        return TranslationFileScope.sourceOf(psi) != null
    }

    /** [revision] with its content read once, now. */
    private fun loaded(revision: ContentRevision): ContentRevision {
        val text = revision.content
        return object : ContentRevision {
            override fun getContent(): String? = text
            override fun getFile(): FilePath = revision.file
            override fun getRevisionNumber(): VcsRevisionNumber = revision.revisionNumber
        }
    }
}
