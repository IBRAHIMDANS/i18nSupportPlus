package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.default
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Configuration holder
 */
data class Config (
    val searchInProjectOnly: Boolean = true,
    val nsSeparator: String = ":",
    val keySeparator: String = ".",
    val pluralSeparator: String = "-",
    val defaultNs: String = "translation",
    val firstComponentNs: Boolean = false,
    val jsConfiguration: String = "",
    val foldingEnabled: Boolean = false,
    val foldingPreferredLanguage: String = "en",
    val foldingMaxLength: Int = 20,
    val extractSorted: Boolean = false,
    val gettext: Boolean = false,
    val gettextAliases: String = "gettext,_,__",
    val partialTranslationInspectionEnabled: Boolean = false,
    val preferredLocalization: String = "",
    val localizationConfig: Map<String, String> = mapOf(),
    val sortKeysAlphabetically: Boolean = false,
    val previewLocale: String = "",
    val translationsRoot: String = "",
    val excludedDirectories: String = "",
    val gutterIconsEnabled: Boolean = true,
    val modules: List<ModuleConfig> = emptyList(),
    val rules: List<EditorRuleState> = emptyList()
) {

    private val MAX_DEFAULT_NAMESPACES = 100

    /**
     * Gets list of default namespaces
     */
    fun defaultNamespaces(): List<String> =
        defaultNs
            .whenMatches {it.isNotBlank()}
            .default(Config().defaultNs)
            .split("[;|,\\s]".toRegex())
            .filter{it.isNotBlank()}
            .take(MAX_DEFAULT_NAMESPACES)

    /**
     * Gets project's search scope
     */
    fun searchScope(project: Project): GlobalSearchScope =
        if (this.searchInProjectOnly) GlobalSearchScope.projectScope(project)
        else GlobalSearchScope.allScope(project)

    /**
     * Returns the set of directory names to exclude from translation file scanning.
     */
    fun excludedDirectorySet(): Set<String> =
        excludedDirectories
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun getLocalizationSetting(localizationId: String, setting: String): String? {
        return localizationConfig.get(localizationId + "/" + setting)
    }
}
