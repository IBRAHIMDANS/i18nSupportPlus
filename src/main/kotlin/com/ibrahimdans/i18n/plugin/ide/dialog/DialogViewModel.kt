package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * ViewModel for the translation dialog.
 * Handles loading translations from all sources and saving modifications.
 */
class DialogViewModel(private val project: Project) : CompositeKeyResolver<PsiElement> {

    /**
     * Loads all localization sources and their current value for the given key.
     * Returns null for a source if the key is missing in that locale.
     */
    fun loadTranslations(fullKey: FullKey): Map<LocalizationSource, String?> =
        runReadAction {
            val sourceService = project.service<LocalizationSourceService>()
            // Fall back to all sources when namespace lookup yields nothing (e.g. CREATE mode with empty FullKey,
            // or projects using locale-named files like en.json/fr.json instead of namespace-named files).
            val namespaces = listOfNotNull(fullKey.ns?.text)
            val sources = sourceService.findSources(namespaces, project)
                .ifEmpty { if (namespaces.isEmpty()) sourceService.findAllSources(project) else emptyList() }
            sources.associateWith { source ->
                val ref = resolveCompositeKey(fullKey.compositeKey, source)
                if (ref != null && ref.unresolved.isEmpty() && ref.element != null) {
                    readPsiValue(ref.element.value())
                } else {
                    null
                }
            }
        }

    /**
     * Returns the distinct sorted list of namespaces available in the project
     * (file names without extension, e.g. "auth", "common", "errors").
     */
    fun loadNamespaces(): List<String> {
        val sourceService = project.service<LocalizationSourceService>()
        return sourceService.findAllSources(project)
            .map { it.name.substringBeforeLast('.') }
            .distinct()
            .sorted()
    }

    /**
     * Loads all sources matching the given namespace name (file stem).
     * Returns empty values since this is for CREATE mode (no existing key to resolve).
     */
    fun loadSourcesForNamespace(namespace: String): Map<LocalizationSource, String?> {
        val sourceService = project.service<LocalizationSourceService>()
        val sources = sourceService.findSources(listOf(namespace), project)
            .ifEmpty { sourceService.findAllSources(project).filter { it.name.substringBeforeLast('.') == namespace } }
        return sources.associateWith { null }
    }

    /**
     * Parses a raw key string (e.g. "common:menu.home") into a FullKey using the project settings.
     * Returns null if the key cannot be parsed.
     */
    fun parseKey(keyText: String): FullKey? {
        val rawKey = RawKey(listOf(KeyElement.literal(keyText)))
        return RawKeyParser(project).parse(rawKey)
    }

    /**
     * Creates JSON files for the given namespace in each detected locale directory.
     *
     * Detection strategy:
     * 1. If [Settings.translationsRoot] is configured → use "$projectBase/$translationsRoot/$locale/$name.json"
     *    where locales are inferred from existing sources (or ["en", "fr"] as fallback).
     * 2. Otherwise → infer root and locales from existing sources via findAllSources().
     * 3. If no sources found → create "public/locales/en/$name.json" and "public/locales/fr/$name.json".
     *
     * Files are created via IntelliJ VFS to ensure the index is updated.
     */
    fun createNamespace(name: String) {
        val log = logger<DialogViewModel>()
        val config = Settings.getInstance(project).config()
        val basePath = project.basePath ?: return
        val sourceService = project.service<LocalizationSourceService>()

        // Determine (rootPath, locale) pairs where files should be created
        val targets: List<Pair<String, String>> = if (config.translationsRoot.isNotBlank()) {
            // Configured root: $projectBase/$translationsRoot/$locale/$name.json
            // Infer locales from existing sources; fall back to ["en", "fr"]
            val existingSources = sourceService.findAllSources(project)
            val locales = existingSources.map { it.parent }.distinct().filter { it.isNotBlank() }
                .ifEmpty { listOf("en", "fr") }
            val rootPath = "$basePath/${config.translationsRoot}".trimEnd('/')
            locales.map { locale -> Pair(rootPath, locale) }
        } else {
            // No configured root: infer from existing sources
            val existingSources = sourceService.findAllSources(project)
            if (existingSources.isNotEmpty()) {
                // displayPath is like "public/locales/en/auth.json" (relative to project base)
                // parent field is the locale dir name; we reconstruct the root from displayPath
                existingSources.map { source ->
                    val locale = source.parent
                    val relPath = source.displayPath  // "locales/en/auth.json" or similar
                    // Root is displayPath minus "/$locale/$filename"
                    val rootSegment = relPath.substringBeforeLast("/$locale/", relPath.substringBeforeLast("/"))
                    val rootPath = if (rootSegment.startsWith("/")) rootSegment else "$basePath/$rootSegment"
                    Pair(rootPath.trimEnd('/'), locale)
                }.distinctBy { (root, locale) -> "$root/$locale" }
            } else {
                // No sources at all: use default structure
                val rootPath = "$basePath/public/locales"
                listOf(Pair(rootPath, "en"), Pair(rootPath, "fr"))
            }
        }

        ApplicationManager.getApplication().runWriteAction {
            for ((rootPath, locale) in targets) {
                try {
                    val dirPath = "$rootPath/$locale"
                    val dir = VfsUtil.createDirectoryIfMissing(dirPath)
                    if (dir == null) {
                        log.warn("createNamespace: could not create directory $dirPath")
                        continue
                    }
                    // Skip if file already exists
                    if (dir.findChild("$name.json") != null) continue
                    val file = dir.createChildData(this, "$name.json")
                    file.setBinaryContent("{}\n".toByteArray())
                } catch (e: Exception) {
                    log.error("createNamespace: failed to create $rootPath/$locale/$name.json", e)
                }
            }
        }
        LocalFileSystem.getInstance().refresh(false)
    }

    /**
     * Saves a translation value for the given source.
     * If the key already exists, the value is updated in place.
     * If the key is missing (unresolved), the key chain is created.
     */
    fun saveTranslation(source: LocalizationSource, fullKey: FullKey, value: String) {
        val ref = resolveCompositeKey(fullKey.compositeKey, source) ?: return
        val generator = source.localization.contentGenerator()
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                ApplicationManager.getApplication().runWriteAction {
                    if (ref.unresolved.isEmpty() && ref.element != null) {
                        // Key exists — update value in place
                        val element = ref.element.value()
                        updatePsiValue(element, value)
                    } else if (ref.element != null) {
                        // Key partially resolved — generate missing chain
                        if (generator.isSuitable(ref.element.value())) {
                            generator.generate(ref.element.value(), fullKey, ref.unresolved, value)
                        }
                    }
                }
            },
            "Update Translation",
            UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION
        )
    }

    /**
     * Reads the text value from a PSI element, handling JSON and YAML types.
     */
    private fun readPsiValue(element: PsiElement): String? =
        when (element) {
            is JsonStringLiteral -> element.value
            is YAMLKeyValue -> element.valueText
            else -> element.text
        }

    /**
     * Updates the text value of a PSI element in place.
     * Falls back to no-op if the element type is not handled.
     */
    private fun updatePsiValue(element: PsiElement, newValue: String) {
        when (element) {
            is JsonStringLiteral -> {
                val generator = com.intellij.json.psi.JsonElementGenerator(project)
                val newLiteral = generator.createStringLiteral(newValue)
                element.replace(newLiteral)
            }
            is YAMLKeyValue -> {
                val key = element.keyText
                val generator = org.jetbrains.yaml.YAMLElementGenerator.getInstance(project)
                val newKeyValue = generator.createYamlKeyValue(key, newValue)
                val newValue2 = newKeyValue.value
                if (newValue2 != null) {
                    element.value?.replace(newValue2)
                }
            }
            else -> {
                // Unsupported element type — no update
            }
        }
    }
}
