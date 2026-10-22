package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** The file layout a framework documents: the templates a module of that framework starts from. */
data class LayoutPreset(
    val pathTemplate: String = "",
    val fileTemplate: String = "",
    val keyTemplate: String = ""
)

/**
 * The file layout of each framework a module preset names, read from `presets/layout-presets.json`.
 *
 * A preset used to select the framework only, and the path, file and key templates stayed to be
 * typed. The layouts are data, each one traced to the framework's documentation in its `source`
 * field, so adding one takes no code. A framework documenting no layout (svelte-i18n, i18n-js)
 * has no entry: choosing it leaves the templates alone.
 */
object LayoutPresets {

    /** The template a module added by hand starts from, before any preset is chosen. */
    const val NEW_MODULE_PATH_TEMPLATE = "{lang}/{ns}.json"

    private const val RESOURCE = "/presets/layout-presets.json"

    /** Layouts by preset id, the keys of [FrameworkDetector.LABELS]. */
    val all: Map<String, LayoutPreset> by lazy { load() }

    /** The layout [preset] documents, or null when it documents none. */
    fun of(preset: String): LayoutPreset? = all[preset.trim()]

    /** [module] with [layout]'s templates; the rest of it unchanged. */
    fun apply(module: ModuleConfig, layout: LayoutPreset): ModuleConfig =
        module.copy(pathTemplate = layout.pathTemplate, fileTemplate = layout.fileTemplate, keyTemplate = layout.keyTemplate)

    /** Whether [module]'s templates are exactly those of [layout]. */
    fun matches(module: ModuleConfig, layout: LayoutPreset): Boolean = templatesOf(module) == layout

    /**
     * Whether [module]'s templates were set by hand and no longer follow its preset's layout —
     * the settings then show the preset as *Custom*. A preset without a layout is never custom.
     */
    fun isCustom(module: ModuleConfig): Boolean {
        val layout = of(module.preset) ?: return false
        return !matches(module, layout)
    }

    /**
     * Whether choosing a preset may overwrite [module]'s templates without asking: they are
     * empty, still the new module's default, or some preset's layout. Anything else was typed
     * by hand, and losing it silently is what a preset must never do.
     */
    fun isReplaceable(module: ModuleConfig): Boolean {
        val templates = templatesOf(module)
        return templates == LayoutPreset() ||
            templates == LayoutPreset(pathTemplate = NEW_MODULE_PATH_TEMPLATE) ||
            all.values.any { it == templates }
    }

    private fun templatesOf(module: ModuleConfig) =
        LayoutPreset(module.pathTemplate.trim(), module.fileTemplate.trim(), module.keyTemplate.trim())

    /** One entry as written in the file: Gson leaves an absent field null, whatever Kotlin declares. */
    private class Entry(val pathTemplate: String?, val fileTemplate: String?, val keyTemplate: String?)

    private fun load(): Map<String, LayoutPreset> {
        val text = LayoutPresets::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: return emptyMap()
        val type = object : TypeToken<Map<String, Entry>>() {}.type
        return Gson().fromJson<Map<String, Entry>>(text, type).mapValues { (_, entry) ->
            LayoutPreset(entry.pathTemplate.orEmpty(), entry.fileTemplate.orEmpty(), entry.keyTemplate.orEmpty())
        }
    }
}
