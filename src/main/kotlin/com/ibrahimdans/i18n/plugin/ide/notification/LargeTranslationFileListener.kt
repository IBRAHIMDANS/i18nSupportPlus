package com.ibrahimdans.i18n.plugin.ide.notification

import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.psi.SingleRootFileViewProvider
import kotlinx.coroutines.launch

/**
 * Tells about a translation file that goes past the IDE's intellisense size limit during the
 * session — keys added in bulk, a generated catalogue, a pull — instead of at the next restart,
 * which [LargeTranslationFileNotifier] alone waited for.
 *
 * Only the size is checked on the EDT, an attribute of the file; whether a large file is a
 * translation source is told in a read action in the background. Registered in plugin.xml as a
 * project listener of [BulkFileListener].
 */
class LargeTranslationFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode) return
        val touched = largeFilesOf(events, SingleRootFileViewProvider::isTooLargeForIntelligence)
        if (touched.isEmpty()) return
        PluginCoroutineScope.of(project).launch {
            val files = readAction { if (project.isDisposed) emptyList() else LargeTranslationFileNotifier.oversizedAmong(project, touched) }
            files.forEach { LargeTranslationFileNotifier.notifyOnce(project, it) }
        }
    }

    companion object {

        /** The files [events] wrote, created, copied or moved that are above the limit, each once. */
        internal fun largeFilesOf(events: List<VFileEvent>, isTooLarge: (VirtualFile) -> Boolean): Set<VirtualFile> =
            events.mapNotNullTo(LinkedHashSet()) { event ->
                val file = when (event) {
                    is VFileContentChangeEvent -> event.file
                    is VFileCreateEvent -> event.file
                    is VFileCopyEvent -> event.findCreatedFile()
                    is VFileMoveEvent -> event.file
                    else -> null
                }
                file?.takeIf { it.isValid && !it.isDirectory && isTooLarge(it) }
            }
    }
}
