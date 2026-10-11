package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent

/**
 * Moves only when what [LocalizationSourceService] reads through the file index may have changed:
 * the content of a file of a [com.ibrahimdans.i18n.Localization] type, or the project's file
 * structure (a file added, removed, renamed or moved — which can change its namespace or locale).
 *
 * The scan used to be stamped with the project-wide PSI modification count, which every keystroke
 * moves: typing in a component dropped every cached lookup, and the next pass recomputed one per
 * namespace its keys named. A keystroke in a `.tsx` moves nothing here.
 *
 * A dedicated listener rather than `PsiModificationTracker.forLanguage`: the formats are several
 * (JSON, YAML, PO, the last two from optional plugins), and structural events carry no file — a
 * new `fr/common.json` must count whichever language it is. The VFS structure count backs up the
 * PSI events, which are only sent for directories the PSI has already loaded.
 *
 * A file of such a type counts only when its [com.ibrahimdans.i18n.Localization] accepts it
 * ([com.ibrahimdans.i18n.Localization.matches], asked with the file's own name): PHP is such a type
 * for Laravel's `lang/<locale>/<file>.php`, and every keystroke in a controller used to drop the scan —
 * whose next run searched the index for all the project's PHP files. A PHP file outside a locale
 * directory is never read as a tree by the scan, whatever it holds, so its content cannot change
 * it. Becoming or ceasing to be a translation file (created, deleted, renamed, moved) is
 * structural, and still always counted.
 *
 * What lives in JS/TS files — a TS catalog, an i18next configuration — is not covered on purpose:
 * those sources stay stamped with the project-wide count (see [LocalizationSourceService]). The TS
 * Localization declares no file type, so a keystroke in a `.ts` moves nothing here either.
 */
@Service(Service.Level.PROJECT)
class TranslationModificationTracker(project: Project) : ModificationTracker, Disposable {

    private val changes = SimpleModificationTracker()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
            override fun childMoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = onChange(event)
        }, this)
    }

    override fun getModificationCount(): Long =
        changes.modificationCount + VirtualFileManager.getInstance().structureModificationCount

    /** A change inside no file is structural (a file or directory added, removed, renamed): rare, always counted. */
    private fun onChange(event: PsiTreeChangeEvent) {
        val file = event.file
        if (file == null || isTranslationFile(file)) changes.incModificationCount()
    }

    /**
     * Whether a [com.ibrahimdans.i18n.Localization] of [file]'s type may read it. Its own name stands
     * for the namespace list, so only what the Localization checks beyond the name decides — the
     * locale directory for PHP; a JSON or YAML file of its extension is always accepted. Cheap: the
     * path and name only, never the content.
     *
     * Counted by type alone, as before, when `matches` cannot tell: a file with no virtual file, or
     * one of the type under another extension (`.babelrc` is JSON) — `matches` compares names built
     * from the declared extensions and would reject it, while the full scan reads it by type.
     */
    private fun isTranslationFile(file: PsiFile): Boolean {
        val fileType = file.fileType
        val virtualFile = file.originalFile.virtualFile
        return Extensions.LOCALIZATION.extensionList.any { localization ->
            localization.types().any { type ->
                type.languageFileType == fileType &&
                    (virtualFile == null || virtualFile.extension !in type.extensions() ||
                        localization.matches(type, virtualFile, listOf(virtualFile.nameWithoutExtension)))
            }
        }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): TranslationModificationTracker = project.service()
    }
}
