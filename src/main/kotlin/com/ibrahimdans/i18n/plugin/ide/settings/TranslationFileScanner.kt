package com.ibrahimdans.i18n.plugin.ide.settings

import java.io.File

/**
 * Finds the translation files the setup wizard offers, by walking the conventional folders.
 *
 * UI-free for the same reason as [FrameworkDetector] and [TranslationRootDetector]: the dialog
 * cannot be instantiated headlessly, and `SetupWizardDialogTest` used to carry its own copy of
 * the folder and extension tables — the tests exercised the copy, and would have kept passing
 * had the shipped lists been broken. #155 fixed that for framework detection; the scan tables
 * had the same problem.
 */
object TranslationFileScanner {

    /**
     * `locale` is the GNU GetText and Django convention, `lang` the Laravel and Nuxt one, and
     * all of these are already recognised as catalogue folders by `TsCatalogTechnology`.
     */
    val FOLDER_NAMES = setOf("locales", "locale", "i18n", "translations", "lang", "langs")

    val EXTENSIONS = setOf("json", "yaml", "yml", "po", "pot")

    const val MAX_SCAN_DEPTH = 5

    private val SKIPPED_DIRECTORIES = setOf("node_modules", "build")

    /** Project-relative paths of every translation file found under [base]. */
    fun scan(base: File): List<String> {
        val found = mutableListOf<String>()
        collect(base, base, 0, found)
        return found
    }

    private fun collect(base: File, dir: File, depth: Int, found: MutableList<String>) {
        if (depth > MAX_SCAN_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in FOLDER_NAMES) {
                collectAll(base, child, found)
            } else if (!child.name.startsWith(".") && child.name !in SKIPPED_DIRECTORIES) {
                collect(base, child, depth + 1, found)
            }
        }
    }

    private fun collectAll(base: File, folder: File, found: MutableList<String>) {
        folder.walkTopDown()
            .filter { it.isFile && it.extension in EXTENSIONS }
            .forEach { found.add(it.relativeTo(base).path) }
    }
}
