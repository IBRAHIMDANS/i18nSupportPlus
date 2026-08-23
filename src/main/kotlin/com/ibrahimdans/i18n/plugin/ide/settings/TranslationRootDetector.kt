package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService

/**
 * Derives "Translations root directory" from the files the setup wizard found.
 *
 * Deliberately free of any UI dependency: [SetupWizardDialog] cannot be instantiated in a
 * headless test, so the rule lives here to be tested directly — the same reason
 * [FrameworkDetector] exists.
 *
 * The wizard used to assume a fixed depth of two levels (`locales/<locale>/<ns>.json`) and
 * read the grand-parent of each file. On a flat `locales/fr.json` that grand-parent is null,
 * every candidate was dropped and **nothing was written at all** — after the summary had
 * announced "2 file(s) found" and offered an *Apply* button.
 */
object TranslationRootDetector {

    /** GetText nests its catalogues one extra level: `locales/fr/LC_MESSAGES/messages.po`. */
    private const val GETTEXT_MESSAGES_DIR = "LC_MESSAGES"

    /**
     * Returns the project-relative folder holding [relativeFilePaths], or null when none can
     * be derived — the caller must then say so rather than let the user believe the wizard
     * configured something.
     *
     * Each file is reduced to the folder above its locale segments, so every layout lands on
     * the same root: `locales/fr.json`, `locales/en/common.json` and
     * `locales/fr/LC_MESSAGES/messages.po` all yield `locales`, and `src/locales/en/ns.json`
     * yields `src/locales`. Files disagreeing on that root fall back to their longest common
     * prefix, which on a monorepo holding `apps/web/locales` and `apps/api/locales` widens to
     * `apps` — wider than needed, but it does contain both, and the alternative (picking the
     * most frequent one) would silently drop the other.
     */
    fun detect(relativeFilePaths: List<String>): String? {
        if (relativeFilePaths.isEmpty()) return null
        val roots = relativeFilePaths.map { rootSegmentsOf(it) }
        // A file sitting at the project root leaves nothing to configure: an empty root means
        // "no root directory" to isIncluded, which is not what the user would read on screen.
        if (roots.any { it.isEmpty() }) return null
        return longestCommonPrefix(roots)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("/")
    }

    /**
     * The distinct roots [relativeFilePaths] point at, before any common prefix is taken.
     *
     * [detect] answers with one root and cannot say whether it had to widen to get there: on a
     * monorepo holding `apps/web/locales` and `apps/api/locales` it returns `apps`, which is a
     * *guess* presented exactly like a certainty. This returns both folders, so the caller can
     * say so — and offer one module per root rather than a root wider than any of them.
     *
     * Sorted, so what is shown never depends on the order the file system returned.
     */
    fun candidates(relativeFilePaths: List<String>): List<String> =
        relativeFilePaths
            .map { rootSegmentsOf(it) }
            .filter { it.isNotEmpty() }
            .map { it.joinToString("/") }
            .distinct()
            .sorted()

    /** Folder segments of [path], minus the file name and the locale levels below the root. */
    private fun rootSegmentsOf(path: String): List<String> {
        var segments = path.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() }
            .dropLast(1)
        while (segments.isNotEmpty() && isBelowTheRoot(segments.last())) {
            segments = segments.dropLast(1)
        }
        return segments
    }

    /**
     * The locale check reuses the shared ISO-backed rule rather than matching on shape: a
     * folder named `lang` or `intl` must not be mistaken for a locale and stripped away.
     */
    private fun isBelowTheRoot(segment: String): Boolean =
        segment == GETTEXT_MESSAGES_DIR || LocalizationSourceService.looksLikeLocale(segment)

    private fun longestCommonPrefix(roots: List<List<String>>): List<String> {
        val shortest = roots.minOf { it.size }
        var length = 0
        while (length < shortest && roots.all { it[length] == roots[0][length] }) {
            length++
        }
        return roots[0].take(length)
    }
}
