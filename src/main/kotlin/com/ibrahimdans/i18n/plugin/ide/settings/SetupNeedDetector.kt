package com.ibrahimdans.i18n.plugin.ide.settings

import java.io.File

/**
 * Decides whether the setup wizard is worth suggesting when a project opens.
 *
 * UI-free, and kept out of [SetupWizardStartupActivity] for the same reason as
 * [FrameworkDetector] and [TranslationRootDetector]: a startup activity cannot be exercised
 * headlessly, so the one rule that decides whether the user is interrupted had no test at all.
 *
 * That rule used to be three cumulative conditions on a pristine project, which meant a
 * half-filled configuration — a root typed then abandoned, or a translations folder moved
 * since — never got the suggestion again, however broken the plugin looked.
 */
object SetupNeedDetector {

    /** Why the wizard is being suggested, or [NONE] when it is not. */
    enum class SetupNeed {
        /** Nothing to suggest: the wizard is switched off, or the configuration resolves. */
        NONE,

        /** No translation root is configured at all. */
        NOT_CONFIGURED,

        /** Roots are configured, but none of them can be read. */
        UNRESOLVED
    }

    /**
     * Whether [config] warrants suggesting the wizard.
     *
     * [rootResolves] answers whether a configured root still holds translation files — it is
     * passed in, rather than read here, so the rule itself stays pure. [holdsTranslations] is
     * what the startup activity hands over.
     */
    fun detect(config: Config, rootResolves: (String) -> Boolean): SetupNeed {
        if (!config.setupWizardEnabled) return SetupNeed.NONE
        val roots = configuredRoots(config)
        if (roots.isEmpty()) return SetupNeed.NOT_CONFIGURED
        return if (roots.any(rootResolves)) SetupNeed.NONE else SetupNeed.UNRESOLVED
    }

    /** Every root [config] points at, whether it comes from the global setting or a module. */
    private fun configuredRoots(config: Config): List<String> =
        (listOf(config.translationsRoot) + config.modules.map { it.rootDirectory })
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * True when [root] — read the way the rest of the plugin reads it, absolute or relative to
     * [basePath] — is a directory holding at least one translation file.
     *
     * The walk is bounded and stops on the first match, so a dead root costs a `stat` and a
     * live one costs the few directories before its first file.
     */
    fun holdsTranslations(basePath: String?, root: String): Boolean {
        val dir = if (root.startsWith("/")) File(root) else File(basePath ?: return false, root)
        if (!dir.isDirectory) return false
        return dir.walkTopDown()
            .maxDepth(TranslationFileScanner.MAX_SCAN_DEPTH)
            .any { it.isFile && it.extension.lowercase() in TranslationFileScanner.EXTENSIONS }
    }
}
