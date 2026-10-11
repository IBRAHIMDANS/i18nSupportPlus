package com.ibrahimdans.i18n.plugin.review.git

import com.ibrahimdans.i18n.plugin.review.ReviewerIdentity
import com.ibrahimdans.i18n.plugin.review.VcsReviewerIdentity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUserRegistry
import git4idea.repo.GitRepositoryManager

/**
 * `user.name` / `user.email` of the Git repository holding a module, through Git4Idea's registry.
 *
 * Loaded by `gitConfig.xml` only. On the EDT — where a review is written, inside its command — only
 * the registry's cache is read: running `git config` there would freeze the IDE. The registry fills
 * that cache itself once Git is set up for the project; until then the OS account is used.
 */
class GitReviewerIdentity(private val project: Project) : VcsReviewerIdentity {

    override fun identity(root: VirtualFile): String? {
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(root) ?: return null
        val registry = GitUserRegistry.getInstance(project)
        val user = registry.getUser(repository.root)
            ?: if (ApplicationManager.getApplication().isDispatchThread) null else registry.getOrReadUser(repository.root)
        return user?.let { ReviewerIdentity.format(it.name, it.email) }
    }
}
