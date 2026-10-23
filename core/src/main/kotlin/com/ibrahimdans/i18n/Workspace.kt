package com.ibrahimdans.i18n

/**
 * The project a [TranslationTechnology] reads, without the IDE: the plugin's `IdeWorkspace` wraps
 * an IntelliJ project, a test or a command-line tool passes its own.
 *
 * A [SourceWorkspace] also lists and reads the files [SourceDiscovery] looks through:
 * [NioWorkspace] walks a folder on disk.
 */
interface Workspace {
    /** The project's directory, which project-relative paths start from; empty when it has none. */
    val basePath: String
}

/** A file of a [Workspace]. */
interface WorkspaceFile {
    /** The file's absolute path, `/`-separated. */
    val path: String

    /** The last segment of [path]: `common.json`. */
    val name: String get() = path.substringAfterLast('/')

    /** What follows the last dot of [name] — `json` — or null when it has none. */
    val extension: String? get() = name.lastIndexOf('.').takeIf { it >= 0 }?.let { name.substring(it + 1) }

    /** [name] without its [extension]: `common`. */
    val nameWithoutExtension: String get() = name.lastIndexOf('.').takeIf { it >= 0 }?.let { name.substring(0, it) } ?: name
}
