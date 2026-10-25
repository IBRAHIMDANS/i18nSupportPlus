package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * The folders and files named with a country code (`gb`, `jp`) beside real locales, which no alias of
 * the settings makes one: the check leaves them out, and says so rather than in silence.
 */
internal object UnreadLocales {

    /** Their paths under [root], relative to it and sorted; a name is counted once per folder holding it. */
    fun find(root: Path, config: Config): List<String> {
        val aliases = config.localeAliasMap().keys
        val extensions = CheckFormats.ALL.flatMap { it.extensions }
        val found = sortedSetOf<String>()
        for (file in NioWorkspace(root).files(extensions)) {
            val path = Path.of(file.path)
            // The file itself (`gb.json`), or the folder holding it (`gb/common.json`).
            for (candidate in listOfNotNull(path, path.parent)) {
                val name = if (candidate == path) candidate.nameWithoutExtension else candidate.fileName.toString()
                if (name in aliases || !LocaleNames.isCountryCodeOnly(name)) continue
                if (besideALocale(candidate)) found += root.toAbsolutePath().normalize().relativize(candidate).toString().replace('\\', '/')
            }
        }
        return found.toList()
    }

    private fun besideALocale(candidate: Path): Boolean {
        val folder = candidate.parent ?: return false
        if (!Files.isDirectory(folder)) return false
        return folder.listDirectoryEntries().any { sibling ->
            sibling != candidate && LocaleNames.looksLikeLocale(if (sibling.isDirectory()) sibling.fileName.toString() else sibling.nameWithoutExtension)
        }
    }
}
