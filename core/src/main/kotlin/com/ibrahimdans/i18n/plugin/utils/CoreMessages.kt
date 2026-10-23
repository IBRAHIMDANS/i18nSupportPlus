package com.ibrahimdans.i18n.plugin.utils

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

// Not "messages.CoreBundle": the platform has one, and in a single class path ours would shadow it.
private const val BUNDLE = "messages.I18nCoreBundle"

/**
 * The user-visible strings of the code in `core`, which cannot reach the platform's
 * `DynamicBundle` that the plugin's `PluginBundle` reads.
 *
 * Read in [locale], the JVM's by default. The JVM's locale is not the language of the IDE —
 * a language pack changes the latter only — so the plugin points [locale] at the IDE's one,
 * and a French IDE gets the codecs' errors in French as well.
 *
 * A locale without its own file falls back to the base `I18nCoreBundle.properties`, never to the
 * JVM's locale: an English IDE on a French JVM stays in English, as `DynamicBundle` does.
 */
object CoreMessages {

    @Volatile
    var locale: () -> Locale = Locale::getDefault

    private val noFallback = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    /**
     * The string for [key], with [params] substituted into its placeholders.
     *
     * As in the plugin's bundle, a value is a `MessageFormat` pattern only when it takes
     * [params]: `''` stands for a quote there, and a value without parameters is shown as is.
     */
    fun message(key: String, vararg params: Any): String {
        val locale = locale()
        val value = ResourceBundle.getBundle(BUNDLE, locale, CoreMessages::class.java.classLoader, noFallback).getString(key)
        return if (params.isEmpty()) value else MessageFormat(value, locale).format(params)
    }
}
