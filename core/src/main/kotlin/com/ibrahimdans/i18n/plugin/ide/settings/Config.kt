package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.default
import com.ibrahimdans.i18n.plugin.utils.whenMatches

/**
 * Configuration holder
 */
data class Config (
    val searchInProjectOnly: Boolean = true,
    val nsSeparator: String = ":",
    val keySeparator: String = ".",
    val pluralSeparator: String = "-",
    val defaultNs: String = "translation",
    val fallbackNs: String = "",
    val firstComponentNs: Boolean = false,
    val jsConfiguration: String = "",
    val foldingEnabled: Boolean = false,
    val foldingPreferredLanguage: String = "en",
    val foldingMaxLength: Int = 20,
    val extractSorted: Boolean = false,
    val gettext: Boolean = false,
    val gettextAliases: String = "gettext,_,__",
    val flatKeys: Boolean = false,
    val partialTranslationInspectionEnabled: Boolean = false,
    val preferredLocalization: String = "",
    val localizationConfig: Map<String, String> = mapOf(),
    val sortKeysAlphabetically: Boolean = false,
    val keyOrder: KeyOrder = KeyOrder.IGNORE_CASE,
    val previewLocale: String = "",
    val translationsRoot: String = "",
    val excludedDirectories: String = "",
    val excludedFileExtensions: String = "",
    val keptKeys: String = "",
    val ignoredLocales: String = "",
    val localeAliases: String = "",
    val gutterIconsEnabled: Boolean = true,
    val setupWizardEnabled: Boolean = true,
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

    /** True when a setting [CodeDefaults] can fill is blank: only then is the code worth reading. */
    fun leavesBlankForCode(): Boolean = CODE_FIELDS.any { it.get(this).isBlank() }

    /**
     * This configuration with its blank settings filled from [code], the project's own i18next
     * initialisation. A setting holding a value keeps it: the settings are the user's word.
     */
    fun withCodeDefaults(code: CodeDefaults?): Config =
        if (code == null) this
        else CODE_FIELDS.fold(this) { config, field ->
            val value = field.code(code)
            if (field.get(config).isBlank() && value != null) field.set(config, value) else config
        }

    /**
     * [edited] as it is to be stored, given the [stored] settings and the [code] defaults they were
     * shown with: a setting blank in [stored] that [edited] holds at the value [code] filled in stays
     * blank, so that saving the page or a module never freezes what the code says into the settings.
     */
    fun keepingCodeDefaultsOut(stored: Config, code: CodeDefaults?): Config =
        if (code == null) this
        else CODE_FIELDS.fold(this) { config, field ->
            val value = field.code(code)
            if (field.get(stored).isBlank() && value != null && field.get(config) == value) field.set(config, "") else config
        }

    /** i18next's `fallbackNS` as set in the settings: none by default. */
    fun fallbackNamespaces(): List<String> =
        fallbackNs.split("[;|,\\s]".toRegex()).filter { it.isNotBlank() }.take(MAX_DEFAULT_NAMESPACES)

    /**
     * True when [locale] is one of the locales not checked for missing keys — a translation in
     * progress, or abandoned. Matched as [LocaleMatching] does: `de` covers `de-DE`, `en-GB` does
     * not cover `en-US`.
     */
    fun ignoresLocale(locale: String): Boolean =
        ignoredLocales.split("[;|,\\s]".toRegex()).filter { it.isNotBlank() }
            .any { LocaleMatching.pick(it, listOf(locale)) != null }

    /**
     * [localeAliases] — `gb=en-GB, cn=zh-CN` — as alias -> locale: a folder or a file named after an
     * alias is that locale's, shown under the name it is written with and compared as the locale
     * (its plural forms). For the country codes some projects write in place of a language code.
     */
    fun localeAliasMap(): Map<String, String> =
        localeAliases.split(',', ';')
            .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            .filter { (alias, locale) -> alias.isNotEmpty() && locale.isNotEmpty() }
            .toMap()

    /** The locale [label] stands for: its [localeAliasMap] target, or itself. */
    fun aliasedLocale(label: String): String = localeAliasMap()[label] ?: label

    /**
     * True when a key must be looked up as a single literal instead of being split into a
     * namespace and nested segments.
     *
     * gettext keys are flat by nature — the msgid *is* the key. react-intl / FormatJS
     * projects usually store flat ids too: `"app.header.title"` is one JSON property, not
     * three nested levels, so splitting on the key separator makes every key unresolvable.
     */
    fun usesFlatKeys(): Boolean = gettext || flatKeys

    /**
     * Returns the set of directory names to exclude from translation file scanning.
     */
    fun excludedDirectorySet(): Set<String> =
        excludedDirectories
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun excludedFileExtensionSet(): Set<String> =
        excludedFileExtensions
            .split(",")
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun getLocalizationSetting(localizationId: String, setting: String): String? {
        return localizationConfig.get(localizationId + "/" + setting)
    }

    /** A setting [CodeDefaults] fills when it is blank, and the value of [CodeDefaults] it takes. */
    private class CodeField(
        val get: (Config) -> String,
        val set: (Config, String) -> Config,
        val code: (CodeDefaults) -> String?,
    )

    private companion object {
        val CODE_FIELDS = listOf(
            CodeField({ it.defaultNs }, { c, v -> c.copy(defaultNs = v) }, { it.defaultNs.joinToString(",").ifEmpty { null } }),
            CodeField({ it.foldingPreferredLanguage }, { c, v -> c.copy(foldingPreferredLanguage = v) }, { it.referenceLocale }),
        )
    }
}

/**
 * What the project's own i18next initialisation states, for the settings left blank: `defaultNS`
 * and `fallbackLng` of `i18next.init({…})` in [origin], a project-relative path shown with the
 * values. See [Config.withCodeDefaults].
 */
data class CodeDefaults(
    val origin: String,
    val defaultNs: List<String> = emptyList(),
    val referenceLocale: String? = null,
)
