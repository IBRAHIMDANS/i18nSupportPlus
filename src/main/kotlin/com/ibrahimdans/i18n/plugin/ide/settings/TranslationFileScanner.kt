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

    /**
     * Bounds both halves of the walk: how deep a translation folder is looked for, and how deep
     * one is then read. Only the first was bounded, so a `locales` folder holding a checkout or
     * a build cache was walked whole, on the EDT.
     */
    const val MAX_SCAN_DEPTH = 5

    /**
     * Dependency and output directories, which hold translation files by the thousand and never
     * the project's own. `node_modules` and `build` were the only two listed, so a `dist`, an
     * `out` or a `vendor` was walked in full before finding nothing worth offering.
     */
    private val SKIPPED_DIRECTORIES =
        setOf("node_modules", "build", "dist", "out", "target", "vendor", "coverage")

    /**
     * Project-relative paths of every translation file found under [base].
     *
     * [checkCanceled] is called as the walk goes, and is expected to throw when the user cancels —
     * it is `ProgressManager::checkCanceled` at runtime, and nothing in tests. The scanner takes
     * it as a lambda rather than reaching for `ProgressManager` itself so it stays headless.
     */
    fun scan(base: File, checkCanceled: () -> Unit = {}): List<String> {
        val found = mutableListOf<String>()
        collect(base, base, 0, found, checkCanceled)
        return found
    }

    private fun collect(base: File, dir: File, depth: Int, found: MutableList<String>, checkCanceled: () -> Unit) {
        if (depth > MAX_SCAN_DEPTH) return
        checkCanceled()
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in FOLDER_NAMES) {
                collectAll(base, child, found, checkCanceled)
            } else if (!child.name.startsWith(".") && child.name !in SKIPPED_DIRECTORIES) {
                collect(base, child, depth + 1, found, checkCanceled)
            }
        }
    }

    private fun collectAll(base: File, folder: File, found: MutableList<String>, checkCanceled: () -> Unit) {
        for (file in folder.walkTopDown().maxDepth(MAX_SCAN_DEPTH)) {
            checkCanceled()
            if (file.isFile && file.extension in EXTENSIONS) {
                found.add(file.relativeTo(base).path)
            }
        }
    }
}
