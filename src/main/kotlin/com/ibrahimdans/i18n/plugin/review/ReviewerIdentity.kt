package com.ibrahimdans.i18n.plugin.review

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Who the version control says the person is, for the repository holding a directory. Implemented
 * on Git4Idea by `GitReviewerIdentity`, registered in `gitConfig.xml` only: without the Git plugin,
 * no service answers and [ReviewerIdentity] falls through to the OS account.
 */
interface VcsReviewerIdentity {
    /** `Name <email>` of [root]'s repository, or null when it names nobody or cannot say right now. */
    fun identity(root: VirtualFile): String?
}

/**
 * The name a review is signed with, the first one known of:
 * 1. the name typed in *Settings | Tools | i18n Support Plus | Reviews* ([ReviewerSettings]);
 * 2. `user.name` / `user.email` of the Git repository holding the module ([VcsReviewerIdentity]);
 * 3. the OS account (`user.name` system property).
 *
 * Declarative, like i18n Ally's: anyone can type any name. The commit that ships the review file
 * is what proves who wrote it.
 */
object ReviewerIdentity {

    const val UNKNOWN = "unknown"

    fun current(project: Project, root: VirtualFile?): String = resolve(
        configured = ReviewerSettings.getInstance().name,
        vcs = { root?.let { project.getService(VcsReviewerIdentity::class.java)?.identity(it) } },
        os = System.getProperty("user.name")
    )

    /** The first non-blank of the three; [vcs] is only asked when no name is configured. */
    internal fun resolve(configured: String?, vcs: () -> String?, os: String?): String =
        configured?.trim()?.takeIf { it.isNotEmpty() }
            ?: vcs()?.trim()?.takeIf { it.isNotEmpty() }
            ?: os?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN

    /** `Name <email>`, either alone when the other is blank, or null when both are. */
    fun format(name: String?, email: String?): String? {
        val n = name?.trim().orEmpty()
        val e = email?.trim().orEmpty()
        return when {
            n.isNotEmpty() && e.isNotEmpty() -> "$n <$e>"
            n.isNotEmpty() -> n
            e.isNotEmpty() -> "<$e>"
            else -> null
        }
    }
}
