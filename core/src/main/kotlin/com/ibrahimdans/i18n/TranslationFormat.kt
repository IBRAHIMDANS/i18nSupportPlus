package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.ConfigurationProperty

/**
 * The part of a translation file format that needs no IDE: its identity and settings.
 * The plugin's `Localization` adds what reads and writes the files.
 */
interface TranslationFormat {
    fun config(): LocalizationConfig
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
