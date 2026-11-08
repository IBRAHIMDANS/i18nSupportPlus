package com.ibrahimdans.i18n.extensions.technology.typesafei18n

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Where typesafe-i18n's generator writes, found from the files alone: what the trunk asks to know
 * whether a project uses the library ([com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages]),
 * without the JavaScript PSI the technology reads the locale modules with.
 */
object TypesafeI18nOutput {

    /** The files typesafe-i18n's generator writes next to the locale folders. */
    private val GENERATED_FILES = listOf("i18n-types.ts", "i18n-util.ts", "i18n-util.js")

    private const val CONFIG_FILE = ".typesafe-i18n.json"
    private const val DEFAULT_OUTPUT = "src/i18n"
    private val OUTPUT_PATH = Regex(""""outputPath"\s*:\s*"([^"]+)"""")

    /** Every output folder of [project]: named by a `.typesafe-i18n.json`, or holding the generated files. */
    fun folders(project: Project): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val configured = FilenameIndex.getVirtualFilesByName(CONFIG_FILE, scope).mapNotNull { config ->
            val text = runCatching { VfsUtilCore.loadText(config) }.getOrNull() ?: return@mapNotNull null
            val output = OUTPUT_PATH.find(text)?.groupValues?.get(1) ?: DEFAULT_OUTPUT
            config.parent?.findFileByRelativePath(output.removePrefix("./").trimEnd('/'))
        }
        val generated = GENERATED_FILES.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }.mapNotNull { it.parent }
        return (configured + generated).filter { "/node_modules/" !in it.path }.distinct().sortedBy { it.path }
    }
}
