package com.ibrahimdans.i18n.plugin.ide.whatsnew

import java.util.Properties

/**
 * The version of this plugin, written into a resource at build time from `pluginVersion`.
 *
 * Asking the platform does not work across 2025.1–2026.3: `PluginManagerCore.getPlugin` and
 * `getPlugins` are reported as internal API, `PluginManager.findEnabledPlugin` and
 * `getPluginByClass` became `@Internal` too, and `PluginDetailsService` only exists from 2026.3.
 * A Kotlin `PluginId.getId` also broke 2025.1 once (#267). Reading a resource of our own jar
 * calls no platform API at all.
 */
object PluginVersion {

    private const val RESOURCE = "/com/ibrahimdans/i18n/plugin-version.properties"

    /** The version, or null when the resource is missing or was not expanded by the build. */
    val current: String? by lazy { read() }

    private fun read(): String? {
        val stream = PluginVersion::class.java.getResourceAsStream(RESOURCE) ?: return null
        val version = stream.use { Properties().apply { load(it) } }.getProperty("version")
        return version?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("\${") }
    }
}
