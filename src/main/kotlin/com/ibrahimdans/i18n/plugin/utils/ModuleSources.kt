package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

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
     * A file's path: project-relative when the file lives under the project directory, which
     * [anchored] says. An unanchored path (outside the project directory, as in light test
     * fixtures) only needs to end with a template, or to go through a root directory.
     */
    data class FilePath(val path: String, val anchored: Boolean) {
        companion object {
            /** [file]'s path relative to [basePath] when it lives under it, absolute otherwise. */
            fun of(file: VirtualFile, basePath: String): FilePath {
                val anchored = basePath.isNotEmpty() && file.path.startsWith("$basePath/")
                return FilePath(if (anchored) file.path.removePrefix("$basePath/") else file.path, anchored)
            }
        }
    }

    /** The match of [file] against the first module template it fits, or null. */
    fun match(modules: List<ModuleConfig>, file: FilePath): Match? = match(modules, file.path, file.anchored)

    fun match(modules: List<ModuleConfig>, path: String, anchored: Boolean): Match? =
        modules.asSequence()
            .mapNotNull { patternOf(it, anchored) }
            .firstNotNullOfOrNull { pattern -> matchWith(pattern, path.trim('/')) }

    /** The module whose root directory holds [file] — the innermost one when roots nest — or null. */
    fun owner(modules: List<ModuleConfig>, file: FilePath): ModuleConfig? =
        modules.filter { rootOf(it).isNotEmpty() && contains(it, file) }.maxByOrNull { rootOf(it).length }

    /** Whether [module]'s root directory holds [file]. A module without one holds nothing. */
    fun contains(module: ModuleConfig, file: FilePath): Boolean {
        val root = rootOf(module)
        if (root.isEmpty()) return false
        val path = file.path.trim('/')
        return if (file.anchored) path.startsWith("$root/") else "/$path".contains("/$root/")
    }

    private fun rootOf(module: ModuleConfig) = module.rootDirectory.trim().trim('/')

    /**
     * Every source of [project], or only [module]'s. A module whose translations all live outside
     * its root directory (a shared package) keeps the project-wide list, as key resolution does.
     */
    fun sourcesOf(project: Project, module: ModuleConfig?): List<LocalizationSource> {
        val all = project.service<LocalizationSourceService>().findAllSources(project)
        if (module == null) return all
        val basePath = project.basePath ?: ""
        val scoped = all.filter { source ->
            val file = (source.tree?.value() ?: source.host)?.containingFile?.virtualFile ?: return@filter false
            contains(module, FilePath.of(file, basePath))
        }
        return scoped.ifEmpty { all }
    }

    /**
     * The JSON or YAML file behind [source], or null for any other format (PO, a JS object, …),
     * whose keys [com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys] does not read.
     *
     * Checked on the language id rather than on the PSI class: YAML is an optional dependency, and
     * `TranslationFileKeys.translationLeaves` only touches YAML classes for a file that is YAML.
     */
    fun readableFile(source: LocalizationSource): PsiFile? {
        val file = source.tree?.value()?.containingFile ?: return null
        return file.takeIf { it.language.isKindOf(JSON_LANGUAGE) || it.language.id == YAML_LANGUAGE }
    }

    private const val JSON_LANGUAGE = "JSON"
    private const val YAML_LANGUAGE = "yaml"

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
