package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * The documents a write was planned from, as they were read. A tool reads under a read action, off the
 * EDT, then writes on the EDT: [changed] tells there whether a document moved in between, so that what
 * was decided on the old text is never written into the new one.
 */
internal class DocumentStamps(private val stamps: Map<Document, Long>) {

    fun changed(): Boolean = stamps.any { (document, stamp) -> document.modificationStamp != stamp }

    companion object {

        fun of(documents: Collection<Document>): DocumentStamps = DocumentStamps(documents.distinct().associateWith { it.modificationStamp })

        /** The documents of [module]'s translation files, the host component's for a block inside one. Needs a read action. */
        fun of(project: Project, module: ModuleConfig?): DocumentStamps {
            val documents = PsiDocumentManager.getInstance(project)
            return of(TranslationDataLoader.findSources(project, module).mapNotNull { source ->
                source.tree?.value()?.hostFile()?.let(documents::getDocument)
            })
        }
    }
}
