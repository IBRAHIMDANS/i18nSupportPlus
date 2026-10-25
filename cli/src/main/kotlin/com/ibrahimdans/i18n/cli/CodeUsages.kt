package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.NioWorkspace
import com.ibrahimdans.i18n.ParsedFormat
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.nextintl.NextIntlKeyExtractor
import com.ibrahimdans.i18n.extract.vuei18n.VueSfc
import com.ibrahimdans.i18n.index.TreeSitterKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationLeaves
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import java.nio.file.Files
import java.nio.file.Path

/** A call to [key], which the reference locale does not hold, at [line] (1-based) of [file], project-relative. */
data class UnknownKey(val key: String, val file: String, val line: Int)

/**
 * Where the code of a project uses its keys: every JavaScript, TypeScript and Vue file read into a
 * [TreeSitterKeyUsageIndex], the folders [NioWorkspace] prunes and those the settings exclude left out.
 * Keys are spelled as [ProjectSources] spells them.
 *
 * Each file is read with the extractors of its module's preset ([CodeExtractors]): i18next's, next-intl's
 * and vue-i18n's calls, all of them outside a module with a preset. A `.vue` file is read through
 * [VueSfc]: its scripts and its template's expressions.
 */
class CodeUsages(project: Path, config: Config) {

    /** One index per preset, its files read with that preset's extractors. */
    private val indexes: List<TreeSitterKeyUsageIndex>

    /** The text of each file indexed, by project-relative path: where a call's line is read. */
    private val texts: Map<String, String>

    /** The keys of each component's `<i18n>` blocks, by project-relative path: known to its own calls. */
    private val blockKeys: Map<String, Set<String>>

    init {
        val root = project.toAbsolutePath().normalize()
        texts = NioWorkspace(root, SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + config.excludedDirectorySet())
            .files(EXTENSIONS)
            .associate { root.relativize(it.file).joinToString("/") to Files.readString(it.file) }
        val modules = ModuleSources.active(config.modules)
        indexes = texts.entries
            .groupBy { (path, _) -> ModuleSources.owner(modules, ModuleSources.FilePath(path, anchored = true))?.preset?.trim()?.ifEmpty { null } }
            .map { (preset, files) ->
                TreeSitterKeyUsageIndex(config, extractors = CodeExtractors.forPreset(preset)).apply {
                    files.forEach { (path, text) -> update(path, if (path.endsWith(VUE)) VueSfc.codeOf(text) else text) }
                }
            }
        blockKeys = texts.filterKeys { it.endsWith(VUE) }.mapValues { (_, text) -> blockKeysOf(text, config) }.filterValues { it.isNotEmpty() }
    }

    /** The [keys] no call uses, nor any key built at runtime may produce, sorted. */
    fun unused(keys: Collection<String>): List<String> {
        val unseen = keys.filter { key -> indexes.all { it.usages(key).isEmpty() } }
        val reached = indexes.flatMapTo(mutableSetOf()) { it.dynamicallyReached(unseen) }
        return (unseen - reached).sorted()
    }

    /**
     * The calls naming a key that none of [keys] is or holds, by file and line. A component's own
     * `<i18n>` block holds keys for its calls, and next-intl's `t.has(key)` only asks whether one exists.
     */
    fun unknown(keys: Collection<String>): List<UnknownKey> =
        indexes.flatMap { it.unknownUsages(keys) }
            .filterNot { call -> call.key in blockKeys[call.usage.path].orEmpty() || isExistenceCheck(call.usage.path, call.usage.span.start) }
            .map { call ->
                val line = texts.getValue(call.usage.path).substring(0, call.usage.span.start).count { it == '\n' } + 1
                UnknownKey(call.key, call.usage.path, line)
            }
            .sortedWith(compareBy({ it.file }, { it.line }))

    /** True when the literal at [offset] of [path] is the key of a `.has(` call. */
    private fun isExistenceCheck(path: String, offset: Int): Boolean =
        texts.getValue(path).substring(0, offset).trimEnd().endsWith(".${NextIntlKeyExtractor.HAS}(")

    private companion object {
        const val VUE = ".vue"
        val EXTENSIONS = listOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue")

        val I18N_BLOCK = Regex("""<i18n\b([^>]*)>(.*?)</i18n>""", RegexOption.DOT_MATCHES_ALL)
        val YAML_LANG = Regex("""\blang\s*=\s*["']ya?ml["']""")

        /** The keys of [text]'s `<i18n>` blocks, JSON or YAML, under any of their locales: `{ "en": { "a": … } }` gives `a`. */
        fun blockKeysOf(text: String, config: Config): Set<String> = I18N_BLOCK.findAll(text).flatMapTo(mutableSetOf()) { block ->
            val format = if (YAML_LANG.containsMatchIn(block.groupValues[1])) ParsedFormat.YAML else ParsedFormat.JSON
            val tree = format.parse(block.groupValues[2]) ?: return@flatMapTo emptyList()
            val leaves = mutableMapOf<String, MutableMap<String, String>>()
            TranslationLeaves.collect(config, tree, "", "", ParsedLeaves, leaves)
            // The first level names the locale.
            leaves.keys.mapNotNull { key -> key.substringAfter(config.keySeparator, "").ifEmpty { null } }
        }
    }
}
