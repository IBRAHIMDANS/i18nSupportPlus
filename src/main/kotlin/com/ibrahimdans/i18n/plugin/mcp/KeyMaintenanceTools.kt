package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.check.LocaleCheck
import com.ibrahimdans.i18n.plugin.check.LocaleProblems
import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.check.ValueProblem
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeyLevelRename
import com.ibrahimdans.i18n.plugin.ide.actions.MachineFill
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.actions.translationFileFor
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.ibrahimdans.i18n.plugin.mcp.TranslationAgentTools.Refused
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.translate.PlaceholderMask
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.sourcesOf
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.io.File

/**
 * What an AI agent may do to a project's keys, apart from translating them ([TranslationAgentTools]):
 * check every locale, find keys by name or text, find where a key is used, and rename, create or delete
 * keys — each through the plugin's own steps, so an agent gets the guards a user gets in the IDE instead
 * of rewriting files:
 *
 *  - the check is `i18n-check`'s ([LocaleCheck]), with the keys the code no longer uses counted as the
 *    table's *Count Key Usages* counts them;
 *  - a key is found by its name or by the text it holds, so that an agent reuses `common:actions.save`
 *    instead of creating its twin;
 *  - a rename is Shift+F6's ([KeyLevelRename]): every locale and call site, refused when the new key
 *    exists, keys built at runtime listed rather than rewritten;
 *  - a key is created only where nothing holds it yet, with the placeholders of its reference value,
 *    and not while another key already holds its reference text unless the agent insists;
 *  - a key is deleted only while no code refers to it, the keep list and keys built at runtime included.
 *
 * Every write is one command the user undoes with one Ctrl+Z. Modules are named as
 * [TranslationAgentTools] names them. The texts returned are read by the agent: they stay in English.
 */
class KeyMaintenanceTools(private val project: Project) {

    /**
     * [unused] is null when the code was not searched. [values] holds, per locale, what is wrong
     * with the placeholders, tags and ICU of its values ([ValueCheck]). [problems] counts every list.
     */
    data class ModuleCheck(
        val module: String,
        val reference: String,
        val problems: Int,
        val locales: List<LocaleProblems>,
        val unused: List<String>?,
        val values: Map<String, List<ValueProblem>> = emptyMap(),
    )

    data class CheckReport(val problems: Int, val modules: List<ModuleCheck>)

    /** [file] relative to the project, [line] 1-based, [code] the line as written. */
    data class Usage(val file: String, val line: Int, val code: String)

    /** [dynamic]: a key built at runtime (`` t(`status.${'$'}{kind}`) ``) may reach it, which no search lists. */
    data class UsagePage(val module: String, val key: String, val defined: Boolean, val total: Int, val dynamic: Boolean, val usages: List<Usage>)

    /** [dynamicUsages]: the keys built at runtime that may still spell the old key, not rewritten. */
    data class RenameResult(val key: String, val newKey: String, val files: Int, val callSites: Int, val dynamicUsages: List<String>)

    /**
     * [usages] is null when the code was not searched (the IDE indexing) or cannot be; [usageNote] then
     * says why a key no search finds is still not unused.
     */
    data class FoundKey(val key: String, val values: Map<String, String>, val usages: Int? = null, val usageNote: String? = null)

    /** [match] is [BY_KEY] or [BY_VALUE]; [total] counts every match, [keys] the first ones. */
    data class FoundKeys(val module: String, val query: String, val match: String, val locale: String, val total: Int, val keys: List<FoundKey>)

    /**
     * [existing]: the keys of the module already holding the reference value. [refused] says why
     * nothing was created — another key holds the same text — and is absent when the key was created.
     */
    data class CreateResult(
        val key: String,
        val written: List<String>,
        val rejected: Map<String, String>,
        val existing: List<String> = emptyList(),
        val refused: String? = null,
    )

    data class DeleteResult(val deleted: List<String>, val entries: Int, val rejected: Map<String, String>)

    /** A rename planned under a read action, applied on the EDT while its files are still as read. */
    internal class RenamePlan(val result: RenameResult, val plan: KeyLevelRename.Plan, val stamps: Map<Document, Long>)

    private val config: Config get() = Settings.getInstance(project).config()

    // ── Check ───────────────────────────────────────────────────────────────

    /**
     * The missing, orphan and empty keys of every locale of [moduleName], or of every module when
     * null, compared with each module's reference locale, and what is wrong with each locale's
     * values, as `i18n-check` reports them; the locales the project does not check are left out.
     * Needs a read action. The unused keys come from [withUnused].
     */
    fun check(moduleName: String?): List<ModuleCheck> {
        val scopes = if (moduleName.isNullOrBlank()) scopes() else listOf(scope(moduleName))
        val config = config
        return scopes.mapNotNull { (name, module) ->
            val locales = TranslationDataLoader.discoverLocales(project, module)
            val reference = referenceOf(module, locales) ?: return@mapNotNull null
            val checked = { locale: String -> locale == reference || !config.ignoresLocale(locale) }
            val translations = TranslationDataLoader.loadAllTranslations(project, module)
            val problems = LocaleCheck.run(translations, reference, config::aliasedLocale, config::parentLocale)
                .filter { checked(it.locale) }
            val values = ValueCheck.run(translations, reference).filter { (locale, found) -> checked(locale) && found.isNotEmpty() }
            ModuleCheck(name, reference, problems.sumOf { it.count } + values.values.sumOf { it.size }, problems, null, values)
        }
    }

    /**
     * [check] with the keys of its reference locale that no code uses: neither found by a search nor
     * possibly built at runtime, nor kept by the project's keep list. Searches the code: call it off
     * the EDT and outside a read action, which each search takes for itself.
     */
    fun withUnused(check: ModuleCheck): ModuleCheck {
        requireSmartMode()
        val (_, module) = scope(check.module)
        val rows = TableViewModel().loadRows(project, module).filter { check.reference in it.values }
        val unused = TableViewModel().countUsages(project, rows, module).filter { it.usageCount == 0 }.map { it.key }.sorted()
        return check.copy(problems = check.problems + unused.size, unused = unused)
    }

    fun report(checks: List<ModuleCheck>): CheckReport = CheckReport(checks.sumOf { it.problems }, checks)

    // ── Find ────────────────────────────────────────────────────────────────

    /**
     * The keys of [moduleName] that [query] names, at most [limit] of them, sorted by key: a key
     * pattern when it holds `*` or `?` (`common:actions.*`), else a key prefix (`common:actions`).
     * When no key matches either way, the keys whose value in [locale] — the reference one by
     * default — holds [query], case ignored: the values equal to it first, then those holding it as
     * a whole word, then the rest. Each key comes with its value in every locale; [withUsages] adds
     * how often the code uses it. Needs a read action.
     */
    fun find(moduleName: String?, query: String, locale: String?, limit: Int): FoundKeys {
        val text = query.trim().ifEmpty { throw Refused("An empty query finds nothing: give a key, a key prefix or a text") }
        val (name, module) = scope(moduleName)
        val locales = TranslationDataLoader.discoverLocales(project, module)
        val searched = locale?.takeIf { it.isNotBlank() } ?: referenceOf(module, locales) ?: throw Refused("No translations found")
        if (searched !in locales) throw Refused("Unknown locale '$searched'. Locales: ${locales.joinToString()}")
        val translations = TranslationDataLoader.loadAllTranslations(project, module)

        val byKey = keysNamed(text, translations.keys)
        val (match, keys) = if (byKey.isNotEmpty()) BY_KEY to byKey else BY_VALUE to keysHolding(text, searched, translations)
        val page = keys.take(limit.coerceAtLeast(1)).map { FoundKey(it, translations.getValue(it).toSortedMap()) }
        return FoundKeys(name, text, match, searched, keys.size, page)
    }

    /**
     * [found] with how often the code of its module uses each key, the keys built at runtime and
     * those of the keep list told apart. Left as it is while the IDE indexes. Searches the code: call
     * it off the EDT and outside a read action, which each search takes for itself.
     */
    fun withUsages(found: FoundKeys): FoundKeys {
        if (found.keys.isEmpty() || DumbService.isDumb(project)) return found
        val (_, module) = scope(found.module)
        val counted = TableViewModel().countUsages(project, found.keys.map { TranslationRow(it.key, it.values) }, module)
            .associate { it.key to it.usageCount }
        return found.copy(keys = found.keys.map { key ->
            val count = counted[key.key] ?: return@map key
            if (count >= 0) key.copy(usages = count) else key.copy(usageNote = usageNoteOf(count))
        })
    }

    /** The keys [query] names as a pattern (`*`, `?`) or a prefix, sorted. */
    private fun keysNamed(query: String, keys: Collection<String>): List<String> {
        val matches: (String) -> Boolean = if (query.any { it == '*' || it == '?' }) {
            val pattern = Regex(query.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } })
            pattern::matches
        } else {
            { it.startsWith(query) }
        }
        return keys.filter(matches).sorted()
    }

    /** The keys whose value in [locale] holds [text], case ignored: equal first, then as a whole word, then anywhere. */
    private fun keysHolding(text: String, locale: String, translations: Map<String, Map<String, String>>): List<String> {
        val word = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(text)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        return translations.mapNotNull { (key, values) ->
            val value = values[locale]?.trim() ?: return@mapNotNull null
            val rank = when {
                value.equals(text, ignoreCase = true) -> 0
                word.containsMatchIn(value) -> 1
                value.contains(text, ignoreCase = true) -> 2
                else -> return@mapNotNull null
            }
            rank to key
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.second }
    }

    // ── Usages ──────────────────────────────────────────────────────────────

    /**
     * Where the code of [moduleName] uses [key] — written whole, under a hook's namespace or key
     * prefix — at most [limit] of them, and whether a key built at runtime may reach it. Call it off
     * the EDT and outside a read action.
     */
    fun usages(moduleName: String?, key: String, limit: Int): UsagePage {
        requireSmartMode()
        val (name, module) = scope(moduleName)
        val defined = TranslationDataLoader.loadAllTranslations(project, module).containsKey(key)
        val index = IdeKeyUsageIndex(project, module)
        val found = index.readUsages(key, ::usageOf).distinct().sortedWith(compareBy({ it.file }, { it.line }))
        val dynamic = key in index.dynamicallyReached(listOf(key))
        return UsagePage(name, key, defined, found.size, dynamic, found.take(limit.coerceAtLeast(1)))
    }

    private fun usageOf(element: PsiElement): Usage? {
        val file = element.containingFile ?: return null
        val path = file.virtualFile?.path ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val line = document.getLineNumber(element.textOffset)
        val code = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        return Usage(relative(path), line + 1, code.take(MAX_CODE))
    }

    private fun relative(path: String): String {
        val base = project.basePath ?: return path
        return if (path.startsWith("$base/")) File(path).relativeTo(File(base)).path else path
    }

    // ── Rename ──────────────────────────────────────────────────────────────

    /**
     * The rename of [key] to [newKey] in [moduleName]: its last segment only — a key is renamed in
     * place, not moved to another level or namespace — in every locale and call site, a whole level
     * (`button` holding `button.*`) or a plural group (`item` for `item_one`, `item_other`) included.
     * Refused when [newKey] exists, or when nothing holds [key]. Needs a read action.
     */
    internal fun planRename(moduleName: String?, key: String, newKey: String): RenamePlan {
        requireSmartMode()
        val (_, module) = scope(moduleName)
        val config = config
        val oldPath = KeySpelling.segmentsOf(key, config)
        val newPath = KeySpelling.segmentsOf(newKey, config)
        val newName = newPath.last().trim()
        when {
            newName.isEmpty() || newPath.any { it.isEmpty() } -> throw Refused("Invalid new key '$newKey'")
            key == newKey -> throw Refused("The new key is the key itself")
            namespaceOf(key) != namespaceOf(newKey) || oldPath.size != newPath.size || oldPath.dropLast(1) != newPath.dropLast(1) ->
                throw Refused("Only the last segment of a key is renamed: '$key' can become '${spell(key, oldPath.dropLast(1) + newName)}'. Moving a key to another level or namespace is done in the IDE")
        }
        val translations = TranslationDataLoader.loadAllTranslations(project, module)
        val form = PluralKey.stripSuffix(key, config.pluralSeparator)
        if (form != key && key in translations && translations.keys.count { PluralKey.stripSuffix(it, config.pluralSeparator) == form } > 1) {
            throw Refused("'$key' is a plural form: rename its group, '$form', and every form follows")
        }
        val (property, plural) = propertyOf(module, key, translations) ?: throw Refused("No translation holds '$key' in this module")
        val level = KeyLevelRename.inTranslationFile(property, plural) ?: throw Refused("'$key' is not in a translation file of this module")
        val plan = level.plan(newName)
        if (plan.conflict) throw Refused("'$newKey' already exists: a key is never merged into another")
        if (plan.files == 0) throw Refused("No translation holds '$key' in this module")
        val stamps = plan.edits.map { it.document }.distinct().associateWith { it.modificationStamp }
        return RenamePlan(RenameResult(key, newKey, plan.files, plan.callSites, plan.dynamicUsages), plan, stamps)
    }

    /** Applies [plan] in one undoable command; refused when one of its files changed since it was planned. Runs on the EDT. */
    internal fun applyRename(plan: RenamePlan): RenameResult {
        if (plan.stamps.any { (document, stamp) -> document.modificationStamp != stamp }) throw Refused(CHANGED)
        val edits = plan.plan.edits.map { RenameI18nKeyHandler.Edit(it.document, it.range, it.text) }
        RenameI18nKeyHandler().apply(project, edits, PluginBundle.message("action.rename.title"), plan.plan.reviews)
        return plan.result
    }

    /** The property of [key] in [module], and whether it stands for a plural group rather than itself. */
    private fun propertyOf(module: ModuleConfig?, key: String, translations: Map<String, Map<String, String>>): Pair<PsiElement, Boolean>? {
        val sources = namespaceSources(ModuleSources.sourcesOf(project, module), key)
        val cleanup = CleanupUnusedKeysAction()
        cleanup.leafProperties(sources, key, config).firstOrNull()?.let { return it to false }
        val form = translations.keys.sorted().firstOrNull { it != key && PluralKey.stripSuffix(it, config.pluralSeparator) == key } ?: return null
        return cleanup.leafProperties(sources, form, config).firstOrNull()?.let { it to true }
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Creates [key] in [moduleName] with [values] (`locale -> text`), in one undoable command. Refused
     * when a translation holds the key, when it is a level holding other keys or under a key holding
     * a value, or without a value in the reference locale; a value of another locale is refused when
     * its placeholders differ from the reference one's, or when that locale has no file for the key's
     * namespace. While another key already holds the same reference value — compared trimmed and
     * case-sensitive, as the extraction's *Use existing key* compares it — nothing is created and the
     * result lists those keys, unless [allowDuplicate]. Runs on the EDT.
     */
    fun create(moduleName: String?, key: String, values: Map<String, String>, allowDuplicate: Boolean = false): CreateResult {
        val (_, module) = scope(moduleName)
        val config = config
        val path = KeySpelling.segmentsOf(key, config)
        if (KeySpelling.pathOf(key).isBlank() || path.any { it.isBlank() } || key != key.trim()) throw Refused("Invalid key '$key'")
        val locales = TranslationDataLoader.discoverLocales(project, module)
        val reference = referenceOf(module, locales) ?: throw Refused("No translations found")
        values.keys.firstOrNull { it !in locales }?.let { throw Refused("Unknown locale '$it'. Locales: ${locales.joinToString()}") }
        val referenceValue = values[reference]?.takeIf { it.isNotBlank() }
            ?: throw Refused("A value in the reference locale '$reference' is required: the other locales are compared with it")
        val translations = TranslationDataLoader.loadAllTranslations(project, module)
        conflictOf(key, path, translations)?.let { throw Refused(it) }
        val existing = translations.filterValues { it[reference]?.trim() == referenceValue.trim() }.keys.sorted()
        if (existing.isNotEmpty() && !allowDuplicate) {
            return CreateResult(key, emptyList(), emptyMap(), existing, "$DUPLICATE ${existing.joinToString()}")
        }

        val sources = namespaceSources(ReadAction.compute<List<LocalizationSource>, RuntimeException> { TranslationDataLoader.findSources(project, module) }, key)
        val rejected = sortedMapOf<String, String>()
        val accepted = sortedMapOf<String, String>()
        for ((locale, text) in values) {
            rejected[locale] = when {
                text.isBlank() -> EMPTY
                locale != reference && !PlaceholderMask.samePlaceholders(referenceValue, text) -> "$PLACEHOLDERS $referenceValue"
                translationFileFor(key, locale, sources, config) == null -> "No translation file for this key's namespace in '$locale'"
                else -> { accepted[locale] = text; continue }
            }
        }
        if (reference !in accepted) throw Refused(rejected.getValue(reference))
        MachineFill.write(project, module, accepted.mapValues { (_, text) -> mapOf(key to text) }, command = PluginBundle.message("quickfix.create.key"))
        val now = TranslationDataLoader.loadAllTranslations(project, module)[key].orEmpty()
        accepted.keys.filter { now[it] != accepted[it] }.forEach { rejected[it] = NOT_WRITTEN }
        return CreateResult(key, accepted.keys.filter { now[it] == accepted[it] }, rejected, existing)
    }

    /** Why [key], whose levels are [path], cannot be created next to [translations]; null when it can. */
    private fun conflictOf(key: String, path: List<String>, translations: Map<String, Map<String, String>>): String? {
        if (key in translations) return "'$key' already exists: edit its values instead"
        val separator = config.keySeparator.takeUnless { config.usesFlatKeys() || it.isEmpty() }
        if (separator == null) return null
        translations.keys.firstOrNull { it.startsWith(key + separator) }?.let { return "'$key' is a level holding other keys, such as '$it'" }
        return (1 until path.size).map { spell(key, path.take(it)) }.firstOrNull { it in translations }
            ?.let { "'$it' holds a value: it cannot hold '$key'" }
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /**
     * Which of [keys] no code of [moduleName] uses, and why each other one is kept: used, possibly
     * built at runtime, kept by the keep list, unknown. Searches the code: call it off the EDT and
     * outside a read action.
     */
    fun deletable(moduleName: String?, keys: List<String>): Pair<List<String>, Map<String, String>> {
        requireSmartMode()
        val (_, module) = scope(moduleName)
        val translations = TranslationDataLoader.loadAllTranslations(project, module)
        val rejected = sortedMapOf<String, String>()
        keys.filter { it !in translations }.forEach { rejected[it] = UNKNOWN_KEY }
        val rows = keys.distinct().filter { it in translations }.map { TranslationRow(it, translations.getValue(it)) }
        val counted = TableViewModel().countUsages(project, rows, module)
        for (row in counted) {
            val count = row.usageCount
            if (count != 0) rejected[row.key] = usageNoteOf(count) ?: "Still used: $count usage(s), see i18n_key_usages"
        }
        return counted.filter { it.usageCount == 0 }.map { it.key } to rejected
    }

    /** Why a key no search counts may still be used, from the table's usage sentinels; null for a count. */
    private fun usageNoteOf(count: Int): String? = when (count) {
        TableViewModel.KEPT_USAGE -> KEPT
        TableViewModel.DYNAMIC_USAGE -> DYNAMIC
        TableViewModel.NOT_SCANNED -> NOT_SEARCHABLE
        else -> null
    }

    /** Deletes [keys] from every locale of [moduleName], their reviews included, in one undoable command. Runs on the EDT. */
    fun delete(moduleName: String?, keys: List<String>, rejected: Map<String, String>): DeleteResult {
        val (_, module) = scope(moduleName)
        val entries = if (keys.isEmpty()) 0 else CleanupUnusedKeysAction().deleteKeys(project, keys, module)
        return DeleteResult(keys.sorted(), entries, rejected)
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    private fun requireSmartMode() {
        if (DumbService.isDumb(project)) throw Refused(INDEXING)
    }

    private fun referenceOf(module: ModuleConfig?, locales: List<String>): String? =
        ReferenceLocale.of(module, config, locales) ?: locales.firstOrNull()

    /** [key]'s namespace, or null for the default ones. */
    private fun namespaceOf(key: String): String? = KeySpelling.namespaceOf(key)

    /** [key] spelled with its own namespace prefix and [path]. */
    private fun spell(key: String, path: List<String>): String {
        val prefix = key.removeSuffix(KeySpelling.pathOf(key))
        return prefix + path.joinToString(config.keySeparator.ifEmpty { "." })
    }

    /**
     * The files of [sources] that may hold [key]: its namespace's, or for a key naming none the
     * default namespaces' — never another namespace's file holding the same path.
     */
    private fun namespaceSources(sources: List<LocalizationSource>, key: String): List<LocalizationSource> {
        val namespace = namespaceOf(key)
        val defaults = config.defaultNamespaces()
        return sources.filter { source ->
            val sourceNamespace = TranslationDataLoader.extractNamespace(source, defaults.first())
            if (namespace == null) sourceNamespace in defaults else sourceNamespace == namespace
        }
    }

    /** The modules by name, as [TranslationAgentTools] lists them; one unnamed scope without any. */
    internal fun scopes(): List<Pair<String, ModuleConfig?>> {
        val modules = config.modules.filter { it.rootDirectory.isNotBlank() }
        if (modules.size < 2) return listOf(TranslationAgentTools.PROJECT to null)
        return modules.map { it.name.ifBlank { it.rootDirectory } to it }
    }

    private fun scope(name: String?): Pair<String, ModuleConfig?> {
        val scopes = scopes()
        if (name.isNullOrBlank()) {
            return scopes.singleOrNull() ?: throw Refused("Several modules: name one of ${scopes.joinToString { it.first }}")
        }
        return scopes.firstOrNull { it.first == name }
            ?: throw Refused("Unknown module '$name'. Modules: ${scopes.joinToString { it.first }}")
    }

    companion object {
        const val MAX_CODE = 200
        const val EMPTY = "Empty value"
        const val PLACEHOLDERS = "Placeholders differ from the reference value:"
        const val NOT_WRITTEN = "Not written: the file changed meanwhile, check it and send it again"
        const val CHANGED = "A file changed since the rename was planned: nothing was renamed, call again"
        const val INDEXING = "The IDE is indexing the project: call again once it is done"
        const val UNKNOWN_KEY = "No translation holds this key in this module"
        const val KEPT = "Kept by the project's keep list (Keys used outside the code)"
        const val DYNAMIC = "May be built at runtime by a dynamic key, such as t(`status.\${kind}`)"
        const val NOT_SEARCHABLE = "Its usages cannot be searched (Flutter ARB key read from Dart): never deleted"
        const val DUPLICATE = "Not created: the reference value is already held by another key; use it, or call again with allowDuplicate=true:"
        const val BY_KEY = "key"
        const val BY_VALUE = "value"
    }
}
