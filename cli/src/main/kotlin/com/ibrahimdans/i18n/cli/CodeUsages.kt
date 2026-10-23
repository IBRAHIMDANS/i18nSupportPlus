package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.index.TreeSitterKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import java.nio.file.Files
import java.nio.file.Path

/** A call to [key], which the reference locale does not hold, at [line] (1-based) of [file], project-relative. */
data class UnknownKey(val key: String, val file: String, val line: Int)

/**
 * Where the i18next / react-i18next code of a project uses its keys: every JavaScript and TypeScript
 * file read into a [TreeSitterKeyUsageIndex], the folders [NioWorkspace] prunes and those the settings
 * exclude left out. Keys are spelled as [ProjectSources] spells them.
 */
class CodeUsages(project: Path, config: Config) {

    private val index = TreeSitterKeyUsageIndex(config)

    /** The text of each file indexed, by project-relative path: where a call's line is read. */
    private val texts: Map<String, String>

    init {
        val root = project.toAbsolutePath().normalize()
        texts = NioWorkspace(root, SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet())
            .files(EXTENSIONS)
            .associate { root.relativize(it.file).joinToString("/") to Files.readString(it.file) }
        texts.forEach(index::update)
    }

    /** The [keys] no call uses, nor any key built at runtime may produce, sorted. */
    fun unused(keys: Collection<String>): List<String> {
        val unseen = keys.filter { index.usages(it).isEmpty() }
        return (unseen - index.dynamicallyReached(unseen)).sorted()
    }

    /** The calls naming a key that none of [keys] is or holds, by file and line. */
    fun unknown(keys: Collection<String>): List<UnknownKey> =
        index.unknownUsages(keys)
            .map { call ->
                val line = texts.getValue(call.usage.path).substring(0, call.usage.span.start).count { it == '\n' } + 1
                UnknownKey(call.key, call.usage.path, line)
            }
            .sortedWith(compareBy({ it.file }, { it.line }))

    private companion object {
        val EXTENSIONS = listOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    }
}
