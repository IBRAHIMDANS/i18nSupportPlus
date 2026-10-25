package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver

/**
 * The translation files a module's templates designate, and the locale and namespace each path
 * carries.
 *
 * A module's `rootDirectory` + `pathTemplate` (+ `fileTemplate`) — `apps/web/messages/{lang}.json`,
 * `locales/{lang}/{ns}.yml` — were edited, validated and previewed in the settings, and then read by
 * nothing: files were still found by guessing a locale from folder and file names, and a module whose
 * layout the guess did not recognise got no translation at all. The template now decides, and the
 * placeholders give the locale and the namespace instead of the guess.
 *
 * Works on paths: the plugin's `IdeModuleSources` adds what reads the IDE's files and sources.
 */
object ModuleSources {

    /** What a path matching a module template says about itself. */
    data class Match(val locale: String, val namespace: String?)

    private val PLACEHOLDER = Regex("""\{([^{}]*)}""")
    private val LOCALE_NAMES = setOf("lang", "locale")
    private val NAMESPACE_NAMES = setOf("ns", "namespace")

    /**
     * A file's path: project-relative when the file lives under the project directory, which
     * [anchored] says. An unanchored path (outside the project directory, as in light test
     * fixtures) only needs to end with a template, or to go through a root directory.
     */
    data class FilePath(val path: String, val anchored: Boolean) {
        companion object {
            /** [path] relative to [basePath] when it lives under it, absolute otherwise. */
            fun of(path: String, basePath: String): FilePath {
                val anchored = basePath.isNotEmpty() && path.startsWith("$basePath/")
                return FilePath(if (anchored) path.removePrefix("$basePath/") else path, anchored)
            }
        }
    }

    /** The match of [file] against the first module template it fits, or null. */
    fun match(modules: List<ModuleConfig>, file: FilePath): Match? = match(modules, file.path, file.anchored)

    fun match(modules: List<ModuleConfig>, path: String, anchored: Boolean): Match? =
        modules.asSequence()
            .mapNotNull { patternOf(it, anchored) }
            .firstNotNullOfOrNull { pattern -> matchWith(pattern, path.trim('/')) }

    /** The modules not put aside ([ModuleConfig.enabled]). */
    fun active(modules: List<ModuleConfig>): List<ModuleConfig> = modules.filter { it.enabled }

    /** [modules] with the one at [index] active and every other one put aside: work on one application. */
    fun focus(modules: List<ModuleConfig>, index: Int): List<ModuleConfig> =
        modules.mapIndexed { i, module -> module.copy(enabled = i == index) }

    /** [modules], every one of them active. */
    fun activateAll(modules: List<ModuleConfig>): List<ModuleConfig> = modules.map { it.copy(enabled = true) }

    /**
     * [modules] given back the [enabled] states taken before a [focus] or an [activateAll]: what
     * *Undo* restores. Left unchanged when the list no longer has as many modules — one was added
     * or removed meanwhile, and the states would land on the wrong ones.
     */
    fun withEnabled(modules: List<ModuleConfig>, enabled: List<Boolean>): List<ModuleConfig> =
        if (enabled.size != modules.size) modules
        else modules.zip(enabled) { module, state -> module.copy(enabled = state) }

    /**
     * Whether [file] lies under the root directory of a module put aside: its owner, among all
     * [modules], is disabled. A disabled module nested in an active one takes its own files out; an
     * active one nested in a disabled one keeps its own.
     */
    fun isPutAside(modules: List<ModuleConfig>, file: FilePath): Boolean = owner(modules, file)?.enabled == false

    /** The module whose root directory holds [file] — the innermost one when roots nest — or null. */
    fun owner(modules: List<ModuleConfig>, file: FilePath): ModuleConfig? =
        modules.filter { rootOf(it).isNotEmpty() && contains(it, file) }.maxByOrNull { rootOf(it).length }

    /**
     * The one module of [modules] with a root directory — the only one [owner] can name — or null when
     * there are none or several. With it, the tool window, *Fill* and the agents still read the project
     * as one scope, but they take its reference locale and its reviews as the key dialog does.
     */
    fun lone(modules: List<ModuleConfig>): ModuleConfig? = active(modules).filter { rootOf(it).isNotEmpty() }.singleOrNull()

    /** Whether [module]'s root directory holds [file]. A module without one holds nothing. */
    fun contains(module: ModuleConfig, file: FilePath): Boolean {
        val root = rootOf(module)
        if (root.isEmpty()) return false
        val path = file.path.trim('/')
        return if (file.anchored) path.startsWith("$root/") else "/$path".contains("/$root/")
    }

    private fun rootOf(module: ModuleConfig) = module.rootDirectory.trim().trim('/')

    /** Whether any module declares a usable template. */
    fun hasTemplates(modules: List<ModuleConfig>): Boolean = modules.any { patternOf(it, true) != null }

    private fun matchWith(pattern: Regex, path: String): Match? {
        val result = pattern.matchEntire(path) ?: return null
        val locale = result.groups["lang"]?.value ?: return null
        // Asking a matcher for a group its pattern does not declare throws: a template without {ns}.
        val namespace = if ("(?<ns>" in pattern.pattern) result.groups["ns"]?.value else null
        return Match(locale, namespace)
    }

    /**
     * The regex a module's combined template compiles to, or null when the module has no usable
     * template: none written, unbalanced braces, an unknown placeholder, or no locale placeholder
     * (every locale would be the same file, which says nothing about which locale it is).
     */
    private fun patternOf(module: ModuleConfig, anchored: Boolean): Regex? {
        if (module.pathTemplate.isBlank() && module.fileTemplate.isBlank()) return null
        val template = ModuleTemplateResolver.combine(module)
        if (ModuleTemplateResolver.issues(template).isNotEmpty()) return null

        val pattern = StringBuilder(if (anchored) "" else "(?:.*/)?")
        val seen = mutableSetOf<String>()
        var last = 0
        for (placeholder in PLACEHOLDER.findAll(template)) {
            pattern.append(Regex.escape(template.substring(last, placeholder.range.first)))
            val group = when (placeholder.groupValues[1].trim().lowercase()) {
                in LOCALE_NAMES -> "lang"
                in NAMESPACE_NAMES -> "ns"
                else -> return null
            }
            // A placeholder written twice must hold the same value both times.
            pattern.append(if (seen.add(group)) "(?<$group>[^/]+)" else "\\k<$group>")
            last = placeholder.range.last + 1
        }
        pattern.append(Regex.escape(template.substring(last)))
        // A template naming no extension (`{lang}/{ns}`) designates any translation file of that name.
        if (!template.substringAfterLast('/').contains('.')) pattern.append("""\.[^/.]+""")
        return Regex(pattern.toString())
    }
}
