package com.ibrahimdans.i18n.plugin.ide.notification

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.SourceWorkspace
import com.ibrahimdans.i18n.WorkspaceFile
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspaceFile
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.ibrahimdans.i18n.plugin.utils.virtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SingleRootFileViewProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Warns about a translation file above `idea.max.intellisense.filesize` (2.5 MB by default).
 *
 * Past that size the IDE neither indexes nor parses the file, so the source scan, which goes
 * through the file index, never sees it: its keys are reported missing with nothing saying why.
 * One notification per file and per session, with the action that edits the IDE's custom
 * properties, where the limit is raised: when the project opens here, and for a file growing past
 * the limit later on through [LargeTranslationFileListener].
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class LargeTranslationFileNotifier : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val files = readAction { oversizedFiles(project) }
        files.forEach { notifyOnce(project, it) }
    }

    companion object {

        /** The IDE action behind *Help › Edit Custom Properties…*. */
        private const val EDIT_CUSTOM_PROPERTIES = "EditCustomProperties"

        /**
         * The code insight limit in bytes: `idea.max.intellisense.filesize`, the property the
         * notification tells the user to raise. Read through [FileUtilRt], public API on every
         * supported IDE, and not through `FileSizeLimit`, which is internal API up to 2026.1 and
         * had the Marketplace refuse 1.18.0. A format may raise its own limit through the
         * `fileSizeLimit` extension point — none of the bundled translation formats does.
         */
        internal fun intellisenseLimit(): Int = FileUtilRt.getUserFileSizeLimit()

        /**
         * The files of [project] that would be translation sources were they not too large for the
         * IDE. The file index leaves them out, so the project content is walked for files of a
         * translation format above the limit — almost always none — and [SourceDiscovery]'s rules
         * (root, locale guess, modules, exclusions) decide which of them are sources.
         */
        fun oversizedFiles(project: Project): List<VirtualFile> = oversizedFiles(project, SingleRootFileViewProvider::isTooLargeForIntelligence)

        internal fun oversizedFiles(project: Project, isTooLarge: (VirtualFile) -> Boolean): List<VirtualFile> {
            val files = mutableListOf<VirtualFile>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory) files += file
                true
            }
            return oversizedAmong(project, files, isTooLarge)
        }

        /**
         * The [files] that would be translation sources were they not too large for the IDE: the
         * check of [oversizedFiles], restricted to the files a change in the session touched.
         */
        fun oversizedAmong(
            project: Project,
            files: Collection<VirtualFile>,
            isTooLarge: (VirtualFile) -> Boolean = SingleRootFileViewProvider::isTooLargeForIntelligence,
        ): List<VirtualFile> {
            val formats = IdeI18nRegistry.formats().filter { it.scannedByName() }
            val types = formats.flatMap { format -> format.types().map { it.languageFileType } }.toSet()
            val config = Settings.getInstance(project).config()
            val scope = config.searchScope(project)
            val candidates = files.filter { file ->
                file.isValid && !file.isDirectory && file.fileType in types && scope.contains(file) && isTooLarge(file)
            }
            if (candidates.isEmpty()) return emptyList()
            val workspace = OversizedWorkspace(project, candidates)
            val listed = SourceDiscovery(workspace, IdeI18nRegistry, config).scannedSources().mapTo(HashSet()) { it.displayPath }
            return workspace.listedAmong(listed)
        }

        /**
         * Tells about [file] unless this session already did: a file saved again and again above the
         * limit, or found both at startup and by the listener, is told once. True when told.
         */
        fun notifyOnce(project: Project, file: VirtualFile, notify: (Project, VirtualFile) -> Unit = ::notify): Boolean {
            if (!project.service<NotifiedLargeFiles>().add(file)) return false
            notify(project, file)
            return true
        }

        fun notify(project: Project, file: VirtualFile) {
            val limitMb = intellisenseLimit() / (1024.0 * 1024.0)
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PluginNotifications.STICKY)
                .createNotification(
                    PluginBundle.message("notification.largeFile.title"),
                    PluginBundle.message("notification.largeFile.content", file.name, "%.1f".format(limitMb)),
                    NotificationType.WARNING,
                )
            // Added before notify(): a balloon already shown does not pick up a later action.
            ActionManager.getInstance().getAction(EDIT_CUSTOM_PROPERTIES)?.let(notification::addAction)
            notification.notify(project)
        }
    }

    /** The files of the project already told about in this session, by URL. */
    @Service(Service.Level.PROJECT)
    internal class NotifiedLargeFiles {
        private val urls = ConcurrentHashMap.newKeySet<String>()

        /** False when [file] was already told about. */
        fun add(file: VirtualFile): Boolean = urls.add(file.url)
    }

    /**
     * The project as [SourceDiscovery] sees it, holding only [candidates]: the oversized files, which
     * the file index does not list. Nothing is read from them — the IDE gives them no PSI to read.
     */
    private class OversizedWorkspace(project: Project, private val candidates: List<VirtualFile>) :
        IdeWorkspace(project), SourceWorkspace<PsiElement, Localization<PsiElement>> {

        private val fileIndex = ProjectFileIndex.getInstance(project)

        override fun files(format: Localization<PsiElement>): List<WorkspaceFile> {
            val types = format.types().map { it.languageFileType }
            return candidates.filter { it.fileType in types }.map(::IdeWorkspaceFile)
        }

        override fun isNamedAfter(format: Localization<PsiElement>, file: WorkspaceFile, fileNames: List<String>): Boolean =
            format.types().any { type -> format.matches(type, file, fileNames) }

        override fun isExcluded(file: WorkspaceFile): Boolean = fileIndex.isExcluded(file.virtualFile)

        override fun read(format: Localization<PsiElement>, file: WorkspaceFile): Tree<PsiElement>? = null

        /** The [candidates] whose source path is in [displayPaths], each once. */
        fun listedAmong(displayPaths: Set<String>): List<VirtualFile> =
            candidates.filter { file -> displayPaths.any { file.path.endsWith("/" + it.trimStart('.', '/')) } }
    }
}
