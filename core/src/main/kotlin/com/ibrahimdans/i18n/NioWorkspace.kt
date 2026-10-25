package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.tree.Tree
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A translation format read outside the IDE. [JSON] and [YAML], the parsers of `plugin/format`, are
 * built in; their ids are those of the plugin's JSON and YAML localizations, so a configuration's
 * per-format settings apply: as in the plugin, a `.json5` file belongs to the JSON format. A tool
 * with other parsers adds its own formats — `core` has no parser for code, the TS catalogues
 * need tree-sitter.
 */
class ParsedFormat(
    /** What a report calls the format: `JSON`. */
    val name: String,
    private val id: String,
    /** The extensions of its files, without the dot. */
    val extensions: List<String>,
    private val parser: (text: String, extension: String?) -> Tree<ParsedNode>?,
) : TranslationFormat {

    override fun config(): LocalizationConfig = LocalizationConfigImpl(id)

    /**
     * [text] read as a translation tree, or null when it does not parse into one; [extension], the
     * file's without the dot, picks the dialect (`json5` is read leniently, `json` strictly).
     */
    fun parse(text: String, extension: String? = null): Tree<ParsedNode>? = parser(text, extension?.lowercase())

    override fun toString(): String = name

    companion object {
        val JSON = ParsedFormat("JSON", "json", listOf("json", "json5")) { text, extension ->
            JsonTranslationTree.parse(text, json5 = extension == "json5")
        }
        val YAML = ParsedFormat("YAML", "yaml", listOf("yml", "yaml")) { text, _ -> YamlTranslationTree.parse(text) }

        /** The built-in formats. */
        val entries: List<ParsedFormat> = listOf(JSON, YAML)
    }
}

/**
 * A folder on disk as a [SourceWorkspace]: the files are found by walking [root] with `java.nio`, and
 * read by the [ParsedFormat] parsers. What a command-line tool hands [SourceDiscovery]:
 * ```
 * SourceDiscovery(NioWorkspace(Path.of(".")), StaticI18nRegistry(ParsedFormat.entries), config).sources()
 * ```
 *
 * The walk does not go down the [prunedDirectories] — `node_modules` and the like, as
 * [SourceDiscovery.isExcludedPath] tells them: a file there would be excluded anyway, and the folder
 * may hold more files than the whole project. Nothing is cached: each [files] call walks again.
 */
class NioWorkspace(
    root: Path,
    private val prunedDirectories: Set<String> = SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES,
) : SourceWorkspace<ParsedNode, ParsedFormat> {

    private val root: Path = root.toAbsolutePath().normalize()

    override val basePath: String = slashed(this.root)

    override fun files(format: ParsedFormat): List<NioWorkspaceFile> = files(format.extensions)

    /**
     * The files under the root whose extension, without the dot, is one of [extensions] — the
     * source files of a command-line check as well as the translation files.
     */
    fun files(extensions: Collection<String>): List<NioWorkspaceFile> {
        val found = mutableListOf<NioWorkspaceFile>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && SourceDiscovery.isExcludedPath(slashed(root.relativize(dir)), prunedDirectories)) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val candidate = NioWorkspaceFile(file)
                if (attrs.isRegularFile && candidate.extension?.lowercase() in extensions) found += candidate
                return FileVisitResult.CONTINUE
            }

            // An unreadable folder is skipped, not a reason to give up the whole walk.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found.sortedBy { it.path }
    }

    override fun isNamedAfter(format: ParsedFormat, file: WorkspaceFile, fileNames: List<String>): Boolean =
        fileNames.any { fileName -> format.extensions.any { extension -> "$fileName.$extension" == file.name } }

    /** The tree [format] parses in [file]; null when it does not parse, or cannot be read. */
    override fun read(format: ParsedFormat, file: WorkspaceFile): Tree<ParsedNode>? =
        try {
            format.parse(Files.readString(Path.of(file.path)), file.extension)
        } catch (e: IOException) {
            null
        }
}

/** A file found by a [NioWorkspace]. */
class NioWorkspaceFile(val file: Path) : WorkspaceFile {
    override val path: String = slashed(file)
}

private fun slashed(path: Path): String = path.toString().replace(File.separatorChar, '/')
