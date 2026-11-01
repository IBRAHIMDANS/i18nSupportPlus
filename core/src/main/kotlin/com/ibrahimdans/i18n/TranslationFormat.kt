package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.ConfigurationProperty

/**
 * The part of a translation file format that needs no IDE: its identity and settings.
 * The plugin's `Localization` adds what reads and writes the files.
 */
interface TranslationFormat {
    fun config(): LocalizationConfig

    /**
     * Whether [SourceDiscovery] finds this format's files by their name and place — a locale folder, a
     * namespace file. False for a format whose files are sources only where a technology declares them:
     * an XLIFF file is one of Angular's catalogues only when `angular.json` lists it, and the export the
     * plugin itself writes, named `fr.xlf`, must not become one.
     */
    fun scannedByName(): Boolean = true
}

interface LocalizationConfig {
    fun id(): String
    fun props(): List<ConfigurationProperty>
}

data class LocalizationConfigImpl(
    val configId: String,
    val configProps: List<ConfigurationProperty> = emptyList()
) : LocalizationConfig {
    override fun id(): String = configId
    override fun props(): List<ConfigurationProperty> = configProps
}
