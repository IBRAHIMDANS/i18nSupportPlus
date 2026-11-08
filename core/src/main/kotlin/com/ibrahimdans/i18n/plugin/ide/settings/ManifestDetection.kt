package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * The manifests the setup wizard reads from disk: which frameworks a folder declares, the manifests between
 * the project root and the catalogues, and the packages of a pnpm, npm or Yarn workspace.
 *
 * Plain [File] reads, no platform: kept out of the dialog, which cannot be instantiated in a headless test,
 * as [FrameworkDetector] and [WizardPlan] are.
 */
object ManifestDetection {

    /**
     * Manifests whose dependencies give the frameworks away: `package.json` for the JS
     * libraries, `composer.json` for Laravel, a Maven or Gradle build for Spring. [FrameworkDetector]
     * holds one table for all, and reads Angular's own i18n from an `angular.json` listing its translations.
     */
    private val MANIFESTS = listOf("package.json", "composer.json", "angular.json", "pom.xml", "build.gradle", "build.gradle.kts")

    /** How deep below the project root a workspace package is looked for. */
    private const val WORKSPACE_DEPTH = 4

    /** Frameworks declared by the manifests found directly in [base]. */
    fun detectFrameworksIn(base: File): Set<String> =
        MANIFESTS
            .map { File(base, it) }
            .filter { it.isFile }
            .flatMapTo(mutableSetOf()) { FrameworkDetector.detect(it.readText()) }

    /**
     * Project-relative folder -> the text of its manifests, for each folder holding one between
     * [base] (left out: a monorepo's root manifest names no application) and each of [roots],
     * the folders the catalogues sit in. What [WizardPlan.modulesFor] roots the modules at.
     */
    fun manifestsAbove(base: File, roots: List<String>): Map<String, String> {
        val folders = roots.flatMap { root ->
            val segments = root.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            (1..segments.size).map { segments.take(it).joinToString("/") }
        }.distinct()
        return folders.mapNotNull { folder ->
            val texts = MANIFESTS.map { File(base, "$folder/$it") }.filter { it.isFile }.map { it.readText() }
            if (texts.isEmpty()) null else folder to texts.joinToString("\n")
        }.toMap()
    }

    /**
     * Project-relative folder -> `package.json` text, for each package of the workspace [base]
     * declares: the `packages` globs of `pnpm-workspace.yaml`, else the `workspaces` of the root
     * `package.json` (npm, Yarn). Empty without a workspace. What [WizardPlan.codeRootsOf] reads
     * the dependencies from.
     */
    fun workspacePackagesIn(base: File): Map<String, String> {
        val globs = workspaceGlobs(base)
        if (globs.isEmpty()) return emptyMap()
        val (excluded, included) = globs.partition { it.startsWith("!") }
        val fileSystem = FileSystems.getDefault()
        val includes = included.map { fileSystem.getPathMatcher("glob:${it.trim('/')}") }
        val excludes = excluded.map { fileSystem.getPathMatcher("glob:${it.removePrefix("!").trim('/')}") }
        return base.walkTopDown()
            .maxDepth(WORKSPACE_DEPTH)
            .onEnter { it == base || (it.name != "node_modules" && !it.name.startsWith(".")) }
            .filter { it != base && it.isDirectory && File(it, "package.json").isFile }
            .mapNotNull { folder ->
                val relative = folder.relativeTo(base).invariantSeparatorsPath
                val path = Path.of(relative)
                relative.takeIf { includes.any { it.matches(path) } && excludes.none { it.matches(path) } }
                    ?.let { it to File(folder, "package.json").readText() }
            }
            .toMap()
    }

    /** The workspace globs [base] declares, `!` exclusions included. */
    private fun workspaceGlobs(base: File): List<String> {
        val pnpm = File(base, "pnpm-workspace.yaml")
        if (pnpm.isFile) return pnpmPackages(pnpm.readLines())
        val manifest = File(base, "package.json").takeIf { it.isFile } ?: return emptyList()
        val workspaces = try {
            JsonParser.parseString(manifest.readText()).takeIf { it.isJsonObject }?.asJsonObject?.get("workspaces")
        } catch (_: JsonParseException) {
            null
        }
        // npm and Yarn 1 take an array; Yarn also takes `{ "packages": [...] }`.
        val array = when {
            workspaces == null -> null
            workspaces.isJsonArray -> workspaces.asJsonArray
            workspaces.isJsonObject -> workspaces.asJsonObject.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return emptyList()
        return array.filter { it.isJsonPrimitive }.map { it.asString }
    }

    /** The items of the top-level `packages:` list of a `pnpm-workspace.yaml`. */
    private fun pnpmPackages(lines: List<String>): List<String> {
        val start = lines.indexOfFirst { it.trimEnd() == "packages:" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .filterNot { it.isBlank() || it.trimStart().startsWith("#") }
            .takeWhile { it.startsWith(" ") || it.startsWith("-") }
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").substringBefore(" #").trim().trim('\'', '"') }
            .filter { it.isNotEmpty() }
    }
}
