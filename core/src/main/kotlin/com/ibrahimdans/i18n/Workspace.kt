package com.ibrahimdans.i18n

/**
 * The project a [TranslationTechnology] reads, without the IDE: the plugin's `IdeWorkspace` wraps
 * an IntelliJ project, a test or a command-line tool passes its own.
 */
interface Workspace {
    /** The project's directory, which project-relative paths start from; empty when it has none. */
    val basePath: String
}

/** A file of a [Workspace]. */
interface WorkspaceFile {
    /** The file's absolute path, `/`-separated. */
    val path: String
}
