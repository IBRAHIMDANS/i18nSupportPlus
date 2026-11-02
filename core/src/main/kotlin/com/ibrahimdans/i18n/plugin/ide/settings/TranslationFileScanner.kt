package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
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

    /**
     * Folders only some libraries use for their catalogues: next-intl and Paraglide keep theirs in
     * `messages/{lang}.json`. Too common a name (mail templates, logs) to be read in every project,
     * so the wizard passes them to [scan] only when one of these frameworks is detected.
     */
    private val FRAMEWORK_FOLDER_NAMES = mapOf(
        "next-intl" to setOf("messages"),
        "paraglide" to setOf("messages")
    )

    val EXTENSIONS = setOf("json", "yaml", "yml", "po", "pot")

    /** The folders, beyond [FOLDER_NAMES], that the detected [frameworks] keep their catalogues in. */
    fun folderNamesFor(frameworks: Set<String>): Set<String> =
        frameworks.flatMapTo(mutableSetOf()) { FRAMEWORK_FOLDER_NAMES[it].orEmpty() }

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
     *
     * Only consulted while looking for a translation folder: once one is found it is read whole,
     * so Laravel's `lang/vendor/<package>/` overrides are offered while Composer's `vendor/` is
     * never entered.
     */
    private val SKIPPED_DIRECTORIES =
        setOf("node_modules", "build", "dist", "out", "target", "vendor", "coverage")

    /**
     * Project-relative paths of every translation file found under [base], in [FOLDER_NAMES] and
     * [extraFolderNames] (see [folderNamesFor]).
     *
     * Below [base], a folder whose `package.json` declares one of these frameworks adds its folders
     * for its own subtree: a monorepo's root manifest seldom names next-intl, `apps/web/package.json`
     * does, and its `messages/` was never offered. [base]'s own manifest is left to the caller, which
     * lets the user untick what it declares.
     *
     * Java resource bundles (`messages_fr.properties`) are found in any folder walked, since Maven and
     * Gradle keep them in `src/main/resources` rather than in a translation folder; a `.properties`
     * file with no localized sibling (`application.properties`) is configuration, never listed.
     *
     * [checkCanceled] is called as the walk goes, and is expected to throw when the user cancels —
     * it is `ProgressManager::checkCanceled` at runtime, and nothing in tests. The scanner takes
     * it as a lambda rather than reaching for `ProgressManager` itself so it stays headless.
     */
    fun scan(base: File, extraFolderNames: Set<String> = emptySet(), checkCanceled: () -> Unit = {}): List<String> {
        val found = mutableListOf<String>()
        collect(base, base, 0, FOLDER_NAMES + extraFolderNames, found, checkCanceled)
        return found
    }

    private fun collect(
        base: File,
        dir: File,
        depth: Int,
        folderNames: Set<String>,
        found: MutableList<String>,
        checkCanceled: () -> Unit
    ) {
        if (depth > MAX_SCAN_DEPTH) return
        checkCanceled()
        val children = dir.listFiles() ?: return
        val names = if (depth == 0) folderNames else folderNames + manifestFolderNames(dir)
        found += bundleFiles(base, dir)
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name in names) {
                collectAll(base, child, found, checkCanceled)
            } else if (!child.name.startsWith(".") && child.name !in SKIPPED_DIRECTORIES) {
                collect(base, child, depth + 1, names, found, checkCanceled)
            }
        }
    }

    /** The extra folders the `package.json` of [dir], if any, makes catalogue folders. */
    private fun manifestFolderNames(dir: File): Set<String> {
        val manifest = File(dir, "package.json")
        return if (manifest.isFile) folderNamesFor(FrameworkDetector.detect(manifest.readText())) else emptySet()
    }

    private fun collectAll(base: File, folder: File, found: MutableList<String>, checkCanceled: () -> Unit) {
        for (file in folder.walkTopDown().maxDepth(MAX_SCAN_DEPTH)) {
            checkCanceled()
            if (file.isDirectory) {
                found += bundleFiles(base, file)
            } else if (file.extension in EXTENSIONS) {
                found.add(file.relativeTo(base).path)
            }
        }
    }

    /**
     * The bundle files directly in [dir], by [PropertiesBundle.placementsIn]'s rule, the plugin's.
     * The reference locale is not known yet: the default one stands in, which only decides whether
     * `messages.properties` is left out beside a `messages_en.properties`.
     */
    private fun bundleFiles(base: File, dir: File): List<String> {
        val bundles = dir.listFiles { file -> file.isFile && file.extension == PropertiesBundle.EXTENSION }
            ?.associateBy { it.nameWithoutExtension }
            ?: return emptyList()
        if (bundles.isEmpty()) return emptyList()
        return PropertiesBundle.placementsIn(bundles.keys, DEFAULT_REFERENCE_LOCALE).keys
            .map { bundles.getValue(it).relativeTo(base).path }
            .sorted()
    }

    private const val DEFAULT_REFERENCE_LOCALE = "en"
}
