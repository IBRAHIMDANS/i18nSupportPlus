package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
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
 * What the translation dialog's key field holds, checked while the user types.
 * See [DialogViewModel.checkKey] for the rule behind each outcome.
 */
enum class KeyCheck { EMPTY, INVALID_SEGMENT, TAKEN, AVAILABLE }

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
        ReadAction.compute<Map<LocalizationSource, String?>, RuntimeException> {
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
            PluginBundle.message("dialog.translation.command.update"),
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

    // ------------------------------------------------------------------------
    // Live key check and variable comparison
    //
    // The rules themselves sit in the companion object: they are pure functions of their
    // arguments, so they are exercised headlessly instead of through the widget listeners
    // that call them. `TranslationDialog.isValidNamespace` was pulled out of the "+" button's
    // validator for that same reason; this follows the same path.
    // ------------------------------------------------------------------------

    /**
     * The whole project's translations, read once per dialog.
     *
     * Both the key check and the "most complete locale" ask the same question of this map, and
     * the dialog is modal — nothing edits the translation files underneath while it is open.
     * Lazy, so a dialog whose key check and copy button are never used pays nothing for it.
     */
    private val allTranslations: Map<String, Map<String, String>> by lazy {
        TranslationDataLoader.loadAllTranslations(project)
    }

    /**
     * The keys already defined under [namespace], stripped of their namespace prefix.
     *
     * Read through [TranslationDataLoader] rather than re-walking the trees here: it is the one
     * place that already knows how a namespace is spelled inside a full key — prefixed, unless
     * it is one of the project's default namespaces — and the tool window reads the very same
     * map, so the dialog cannot disagree with the tree about what already exists. The `:` below
     * is that map's own spelling, not the configured namespace separator.
     *
     * The loader joins nested levels with a `.`, whatever the project's key separator is, so a
     * project separating keys otherwise gets no "already taken" hint. It errs towards saying
     * nothing rather than towards refusing a key that is in fact free.
     */
    fun existingKeys(namespace: String?): Set<String> {
        val defaultNamespaces = Settings.getInstance(project).config().defaultNamespaces()
        val allKeys = allTranslations.keys
        val prefix = if (namespace.isNullOrBlank() || namespace in defaultNamespaces) "" else "$namespace:"
        return if (prefix.isEmpty()) allKeys.filterNot { it.contains(':') }.toSet()
        else allKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }

    /**
     * The locale whose value the dialog offers to copy into the locales left empty.
     *
     * A module that declares a [ModuleConfig.referenceLocale] decides: that locale is the one
     * translators work from, whether or not it happens to be the fullest. The field defaults to
     * an empty string, and a locale nobody is showing is no use here, so both cases fall back to
     * the most complete locale among the ones the dialog displays.
     *
     * Only that fallback may be shown without the word "reference": nobody declared it, it is
     * merely the fullest one we could find.
     */
    fun localeToCopyFrom(sources: Collection<LocalizationSource>): String? {
        val candidates = sources.map { it.localeLabel() }.toSet()
        val declared = Settings.getInstance(project).config().modules
            .firstNotNullOfOrNull { module -> module.referenceLocale.takeIf { it in candidates } }
        return declared ?: mostCompleteLocale(allTranslations, candidates)
    }

    companion object {

        /**
         * Message variables, in the three shapes the plugin already recognises elsewhere:
         * i18next's `{{count}}`, ICU / react-intl's `{name}`, and printf's `%s` / `%1$s`.
         *
         * The `{{…}}` alternative comes first on purpose: on `{{count}}` the single-brace one
         * would match `{count}` and report a variable no translator ever typed.
         */
        private val VARIABLE_REGEX = Regex("""\{\{[^{}]+}}|\{[^{}]+}|%[0-9]*\$?[sd]""")

        /**
         * Where each message variable sits inside [text], so the dialog can highlight them in
         * place without re-deriving the rule for itself.
         */
        internal fun variableRanges(text: String): List<IntRange> =
            VARIABLE_REGEX.findAll(text).map { it.range }.toList()

        /**
         * The message variables [text] carries.
         *
         * Whitespace inside a variable is dropped before comparing, so `{{ count }}` and
         * `{{count}}` count as one variable rather than two — otherwise [missingVariables]
         * would report a loss on a value that lost nothing.
         */
        internal fun messageVariables(text: String): Set<String> =
            VARIABLE_REGEX.findAll(text)
                .map { match -> match.value.filterNot { it.isWhitespace() } }
                .toSet()

        /**
         * The variables each locale drops, compared against the other locales filled in.
         *
         * The comparison is made against the *union* of the variables found across the filled
         * values rather than against one designated locale: no locale is designated yet (see
         * [localeToCopyFrom]), and a variable any translator kept is a variable the message
         * takes. Blank values are ignored — an untranslated locale has lost nothing, it has
         * simply not been written yet.
         *
         * Only the locales actually missing something are returned.
         */
        internal fun missingVariables(valuesByLocale: Map<String, String>): Map<String, Set<String>> {
            val filled = valuesByLocale.filterValues { it.isNotBlank() }
            if (filled.size < 2) return emptyMap()
            val expected = filled.values.flatMapTo(mutableSetOf()) { messageVariables(it) }
            if (expected.isEmpty()) return emptyMap()
            return filled
                .mapValues { (_, value) -> expected - messageVariables(value) }
                .filterValues { it.isNotEmpty() }
        }

        /**
         * The most complete locale among [candidates]: the one translated for the most keys of
         * [translations], a `key -> (locale -> value)` map as [TranslationDataLoader] builds it.
         *
         * Ties are broken by locale name so two openings of the dialog on an unchanged project
         * always propose the same locale. Returns null only when there is no candidate at all.
         */
        internal fun mostCompleteLocale(
            translations: Map<String, Map<String, String>>,
            candidates: Set<String>
        ): String? =
            candidates
                .map { locale -> locale to translations.values.count { !it[locale].isNullOrBlank() } }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .firstOrNull()
                ?.first

        /**
         * What the key field currently holds.
         *
         * [keyText] is the key without its namespace prefix, [keySeparator] the separator the
         * project nests keys with — empty when the project stores flat keys, in which case the
         * key is a single segment whatever it contains. [existingKeys] are the keys already
         * defined under the selected namespace, so that overwriting one is announced before the
         * save rather than discovered after it.
         */
        internal fun checkKey(keyText: String, keySeparator: String, existingKeys: Set<String>): KeyCheck {
            val trimmed = keyText.trim()
            if (trimmed.isEmpty()) return KeyCheck.EMPTY
            val segments = if (keySeparator.isEmpty()) listOf(trimmed) else trimmed.split(keySeparator)
            if (segments.any { segment -> segment.isBlank() || segment.any(Char::isWhitespace) }) {
                return KeyCheck.INVALID_SEGMENT
            }
            return if (trimmed in existingKeys) KeyCheck.TAKEN else KeyCheck.AVAILABLE
        }
    }
}
