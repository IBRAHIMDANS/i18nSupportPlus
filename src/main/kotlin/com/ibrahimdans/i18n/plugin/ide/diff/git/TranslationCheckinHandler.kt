package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.ide.diff.FileVersions
import com.ibrahimdans.i18n.plugin.ide.diff.LocalTranslationChanges
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChanges
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChangesDialog
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ui.BooleanCommitOption
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gives each commit of the project the *Check translations of changed keys* option. */
class TranslationCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        TranslationCheckinHandler(panel.project)
}

/**
 * *Check translations of changed keys*, an option of the commit: when the reference locale adds or
 * changes a key in this commit and a locale does not follow it in the same commit, the commit stops
 * and says so, with *Review* (the translation changes of the commit) and *Commit Anyway*. Nothing
 * is sent to a translation engine from here.
 *
 * Off by default, remembered per project. Declared in `gitConfig.xml`, next to the other commit-side
 * translation actions; the check itself reads the changes the commit hands over, whatever the VCS.
 */
class TranslationCheckinHandler(private val project: Project) : CheckinHandler(), CommitCheck {

    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.LATE

    override fun isEnabled(): Boolean = CommitTranslationCheck.isEnabled(project)

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent =
        BooleanCommitOption.create(
            project, this, true, PluginBundle.message("diff.commit.check.option"),
            { CommitTranslationCheck.isEnabled(project) },
            { CommitTranslationCheck.setEnabled(project, it) },
        )

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        if (!isEnabled()) return null
        val candidates = readAction { CommitTranslationCheck.translationFiles(project, commitInfo.committedChanges) }
        if (candidates.isEmpty()) return null
        // A before-content may come from the VCS (`git show`): read it off any read action.
        val texts = withContext(Dispatchers.IO) { candidates.map { it.change.beforeRevision?.content } }
        val found = smartReadAction(project) {
            CommitTranslationCheck.compare(project, candidates.zip(texts) { file, before -> file.psi to before })
        }
        return CommitTranslationCheck.problemOf(found)
    }
}

/** The commit-side half of [TranslationCheckinHandler], without the platform's commit machinery. */
internal object CommitTranslationCheck {

    private const val ENABLED_KEY = "com.ibrahimdans.i18n.commit.checkTranslations"

    fun isEnabled(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, false)

    fun setEnabled(project: Project, enabled: Boolean) =
        PropertiesComponent.getInstance(project).setValue(ENABLED_KEY, enabled, false)

    /** A committed change of a translation file, and that file as it is now. */
    class Candidate(val change: Change, val psi: PsiFile)

    /** The changes of [changes] that touch a translation file the plugin reads. Needs a read action. */
    fun translationFiles(project: Project, changes: Collection<Change>): List<Candidate> =
        changes.mapNotNull { change ->
            val file = change.afterRevision?.file?.virtualFile ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            if (TranslationFileScope.sourceOf(psi) == null) null else Candidate(change, psi)
        }

    /** The translation changes of [files], each with its text before the commit. Needs a read action. */
    fun compare(project: Project, files: List<Pair<PsiFile, String?>>): TranslationChanges {
        val versions: List<FileVersions> = files.mapNotNull { (psi, before) -> LocalTranslationChanges.versionsOf(psi, before) }
        return LocalTranslationChanges.compare(project, versions)
    }

    /** What stops the commit: the locales lagging behind the reference in [found], or null. */
    fun problemOf(found: TranslationChanges): CommitProblem? {
        if (found.lagging.isEmpty()) return null
        return LaggingTranslationsProblem(found)
    }
}

/** Keys of the reference changed by the commit that some locales did not follow. */
internal class LaggingTranslationsProblem(val found: TranslationChanges) : CommitProblemWithDetails {

    val keyCount: Int = found.lagging.map { it.namespace to it.path }.distinct().size

    val locales: List<String> = found.lagging.map { it.locale }.distinct().sorted()

    override val text: String
        get() = PluginBundle.message(
            "diff.commit.check.problem", keyCount, found.referenceLocale.orEmpty(), locales.joinToString(", ")
        )

    override val showDetailsAction: String
        get() = PluginBundle.message("diff.commit.check.review")

    override fun showDetails(project: Project) {
        TranslationChangesDialog(project, found).show()
    }

    /** The modal commit dialog: *Review* keeps the commit dialog open behind the changes. */
    override fun showModalSolution(project: Project, commitInfo: CommitInfo): CheckinHandler.ReturnResult {
        val answer = Messages.showYesNoCancelDialog(
            project, text, PluginBundle.message("diff.commit.check.title"),
            PluginBundle.message("diff.commit.check.review"),
            PluginBundle.message("diff.commit.check.anyway"),
            Messages.getCancelButton(), Messages.getWarningIcon(),
        )
        return when (answer) {
            Messages.YES -> {
                showDetails(project)
                CheckinHandler.ReturnResult.CANCEL
            }
            Messages.NO -> CheckinHandler.ReturnResult.COMMIT
            else -> CheckinHandler.ReturnResult.CANCEL
        }
    }
}
