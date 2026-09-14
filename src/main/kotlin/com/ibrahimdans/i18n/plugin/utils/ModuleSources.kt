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
 */
internal object ModuleSources {

    /** What a path matching a module template says about itself. */
    data class Match(val locale: String, val namespace: String?)

    private val PLACEHOLDER = Regex("""\{([^{}]*)}""")
    private val LOCALE_NAMES = setOf("lang", "locale")
    private val NAMESPACE_NAMES = setOf("ns", "namespace")

    /**
     * The match of [path] against the first module template it fits, or null.
     *
     * [path] is project-relative when the file lives under the project directory; [anchored] says
     * so. An unanchored path (outside the project directory, as in light test fixtures) only needs
     * to end with the template.
     */
    fun match(modules: List<ModuleConfig>, path: String, anchored: Boolean): Match? =
        modules.asSequence()
            .mapNotNull { patternOf(it, anchored) }
            .firstNotNullOfOrNull { pattern -> matchWith(pattern, path.trim('/')) }

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
