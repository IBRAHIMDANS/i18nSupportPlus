package com.ibrahimdans.i18n.plugin.ide.settings

import java.io.File

/**
 * Turns a module's root directory and templates into the file they actually point at, and
 * reports what is wrong with them.
 *
 * Deliberately free of Swing and of the IntelliJ VFS: the settings form only *renders* what
 * this object computes, so every rule below is unit-testable on its own. Issues are returned
 * as [TemplateIssue] values rather than as sentences — localizing them is the caller's job.
 */
object ModuleTemplateResolver {

    private const val LANG = "lang"
    private const val LOCALE = "locale"
    private const val NS = "ns"
    private const val NAMESPACE = "namespace"

    /** Placeholder names a template may use, in the order they are offered to the user. */
    val SUPPORTED_PLACEHOLDERS: List<String> = listOf(LANG, LOCALE, NS, NAMESPACE)

    /** How many neighbour files the resolution report lists when the target is missing. */
    private const val MAX_NEIGHBOURS = 5

    private val PLACEHOLDER = Regex("\\{([^{}]*)}")

    enum class IssueKind {
        /** Nothing to resolve: no root directory and no template. */
        BLANK,

        /** A brace opens without closing, closes without opening, or nests. */
        UNBALANCED_BRACES,

        /** A placeholder name outside [SUPPORTED_PLACEHOLDERS]; carried in the argument. */
        UNKNOWN_PLACEHOLDER,

        /** No language placeholder at all: every locale would resolve to the same file. */
        NO_LANGUAGE_PLACEHOLDER
    }

    /** A single problem found in a template, with the offending text when there is one. */
    data class TemplateIssue(val kind: IssueKind, val argument: String = "")

    /** Whether a module's root directory is set, and whether it is still there. */
    enum class RootStatus { UNSET, MISSING, PRESENT }

    /**
     * What a module's templates resolve to for one locale and namespace.
     *
     * [absolutePath] is null when the path is relative and the project directory is unknown;
     * in that case no lookup was performed and [exists] is false.
     */
    data class TemplateResolution(
        val template: String,
        val resolvedPath: String,
        val absolutePath: String?,
        val exists: Boolean,
        val neighbours: List<String>,
        val issues: List<TemplateIssue>
    )

    /**
     * Joins the root directory and the two path templates into the single template the
     * module resolves. Blank parts are skipped, and stray slashes never double up.
     */
    fun combine(module: ModuleConfig): String =
        listOf(module.rootDirectory, module.pathTemplate, module.fileTemplate)
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /** Substitutes the supported placeholders; an unknown one is left as written. */
    fun resolve(template: String, locale: String, namespace: String): String {
        val values = mapOf(
            LANG to locale,
            LOCALE to locale,
            NS to namespace,
            NAMESPACE to namespace
        )
        return PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1].trim().lowercase()] ?: match.value
        }
    }

    /** Everything wrong with [template], in the order it is worth showing. */
    fun issues(template: String): List<TemplateIssue> {
        if (template.isBlank()) return listOf(TemplateIssue(IssueKind.BLANK))

        val issues = mutableListOf<TemplateIssue>()
        if (!bracesAreBalanced(template)) {
            issues.add(TemplateIssue(IssueKind.UNBALANCED_BRACES))
            // Placeholder names cannot be trusted once the braces are broken.
            return issues
        }

        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1].trim().lowercase() }.toList()
        names.filterNot { it in SUPPORTED_PLACEHOLDERS }
            .distinct()
            .forEach { issues.add(TemplateIssue(IssueKind.UNKNOWN_PLACEHOLDER, it)) }

        if (names.none { it == LANG || it == LOCALE }) {
            issues.add(TemplateIssue(IssueKind.NO_LANGUAGE_PLACEHOLDER))
        }
        return issues
    }

    /** Whether [module]'s root directory is set and still exists on disk. */
    fun rootStatus(module: ModuleConfig, basePath: String?): RootStatus {
        val root = module.rootDirectory.trim()
        if (root.isEmpty()) return RootStatus.UNSET
        val directory = fileFor(root, basePath) ?: return RootStatus.UNSET
        return if (directory.isDirectory) RootStatus.PRESENT else RootStatus.MISSING
    }

    /**
     * Resolves [module] for one [locale] / [namespace] pair and looks the result up on disk.
     *
     * When the file is missing but its parent directory exists, the first few files sitting
     * there are reported: that is usually enough to see whether the template is off by a
     * directory level or by an extension.
     */
    fun describe(module: ModuleConfig, locale: String, namespace: String, basePath: String?): TemplateResolution {
        val template = combine(module)
        val resolvedPath = resolve(template, locale, namespace)
        val file = if (resolvedPath.isBlank()) null else fileFor(resolvedPath, basePath)
        val exists = file?.isFile ?: false
        return TemplateResolution(
            template = template,
            resolvedPath = resolvedPath,
            absolutePath = file?.path,
            exists = exists,
            neighbours = if (exists) emptyList() else neighboursOf(file),
            issues = issues(template)
        )
    }

    private fun neighboursOf(file: File?): List<String> {
        val parent = file?.parentFile ?: return emptyList()
        if (!parent.isDirectory) return emptyList()
        return (parent.listFiles() ?: return emptyList())
            .filter { it.isFile }
            .map { it.name }
            .sorted()
            .take(MAX_NEIGHBOURS)
    }

    /** A brace must open, close, and never nest — `{{lang}}` is a mistake, not a template. */
    private fun bracesAreBalanced(template: String): Boolean {
        var open = 0
        template.forEach { character ->
            when (character) {
                '{' -> if (open > 0) return false else open++
                '}' -> if (open == 0) return false else open--
            }
        }
        return open == 0
    }

    private fun fileFor(path: String, basePath: String?): File? {
        val file = File(path)
        if (file.isAbsolute) return file
        if (basePath.isNullOrBlank()) return null
        return File(basePath, path)
    }
}
