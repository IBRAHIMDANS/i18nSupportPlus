package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * Represents a single row in the translation table.
 *
 * @param usageCount Number of usages found in source code.
 *   -2 = reached only through a runtime-built key ([TableViewModel.DYNAMIC_USAGE]),
 *   -1 = not yet scanned, 0 = orphan (unused), ≥1 = used.
 */
data class TranslationRow(val key: String, val values: Map<String, String>, val usageCount: Int = -1)

/**
 * What the namespace combo filters on.
 *
 * The identity of an entry is the type, never the text shown for it. Both used to be the same
 * `String`: [All] was the translated label `toolwindow.table.namespace.all`, compared with `==`
 * to decide whether to filter at all, and [Default] was the literal `"(default)"` — displayed
 * untranslated in a tool window #177 had otherwise localized, and impossible to translate without
 * breaking the comparison that read it back. A project owning a namespace named like either label
 * hit the same collision from the other side.
 */
sealed interface NamespaceFilter {

    /** The text shown in the combo. Never compared against anything. */
    val label: String

    /** Every namespace, i.e. no filtering. */
    data object All : NamespaceFilter {
        override val label: String get() = PluginBundle.message("toolwindow.table.namespace.all")
    }

    /** Keys carrying no namespace prefix at all. */
    data object Default : NamespaceFilter {
        override val label: String get() = PluginBundle.message("toolwindow.table.namespace.default")
    }

    /** One named namespace, the part of a key before its `:`. */
    data class Named(val name: String) : NamespaceFilter {
        override val label: String get() = name
    }
}

/**
 * What a locale cell says about its value.
 *
 * The distinction used to live only in the renderer, as two background tints: a reader who
 * cannot tell them apart — greyscale screen, colour vision deficiency, a theme that flattens
 * both — read three different states as one. Naming the states here lets the renderer pair
 * each with an icon and a word, and lets them be tested without a Swing component.
 */
enum class ValueStatus {
    /** No entry at all for this locale. */
    MISSING,

    /** The entry exists but holds nothing but whitespace. */
    BLANK,

    /** A real value. */
    TRANSLATED,
}

/**
 * What the Usage column knows about a key.
 *
 * [NOT_SCANNED] is not [ORPHAN]: nobody has looked yet, which is the distinction the `—`
 * placeholder was carrying alone.
 *
 * [DYNAMIC] is not [ORPHAN] either, and the distinction is what keeps a live key alive: a key
 * only reached through `t(`status.${'$'}{kind}`)` names nothing a text scan can find, so it was
 * reported as *Unused* — and *Cleanup unused keys*, which takes its candidates from that very
 * count, offered to delete it. See [DynamicKeyUsages].
 */
enum class UsageStatus { NOT_SCANNED, ORPHAN, DYNAMIC, USED }

/**
 * View model for the table-based translation view.
 * Provides a flat list of all translation keys with their locale values.
 */
class TableViewModel {

    /**
     * Loads all translations as a flat list of rows.
     * When [moduleConfig] is non-null, only translations from that module are loaded.
     */
    fun loadRows(project: Project, moduleConfig: ModuleConfig? = null): List<TranslationRow> {
        val data = TranslationDataLoader.loadAllTranslations(project, moduleConfig)
        return data.entries
            .sortedBy { it.key }
            .map { (key, localeValues) -> TranslationRow(key, localeValues) }
    }

    /**
     * Returns all discovered locales for the project (or for the given module).
     */
    fun getLocales(project: Project, moduleConfig: ModuleConfig? = null): List<String> {
        return TranslationDataLoader.discoverLocales(project, moduleConfig)
    }

    /**
     * Returns rows whose key or any translation value contains [query] (case-insensitive).
     * Returns the original list unchanged when [query] is blank.
     */
    fun filter(query: String, rows: List<TranslationRow>): List<TranslationRow> {
        if (query.isBlank()) return rows
        val lowerQuery = query.lowercase()
        return rows.filter { row ->
            row.key.lowercase().contains(lowerQuery) ||
                row.values.values.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * The entries the namespace combo offers for [rows]: [NamespaceFilter.All] first, then
     * [NamespaceFilter.Default] when some key carries no namespace, then each namespace found,
     * sorted.
     */
    fun namespaceFilters(rows: List<TranslationRow>): List<NamespaceFilter> {
        val named = rows.mapNotNull { row ->
            val colonIdx = row.key.indexOf(':')
            if (colonIdx > 0) row.key.substring(0, colonIdx) else null
        }.distinct().sorted().map { NamespaceFilter.Named(it) }

        val default = if (rows.any { !it.key.contains(':') }) listOf(NamespaceFilter.Default) else emptyList()
        return listOf(NamespaceFilter.All) + default + named
    }

    /** Returns the rows [filter] selects. */
    fun filterByNamespace(filter: NamespaceFilter, rows: List<TranslationRow>): List<TranslationRow> =
        when (filter) {
            NamespaceFilter.All -> rows
            NamespaceFilter.Default -> rows.filter { !it.key.contains(':') }
            is NamespaceFilter.Named -> rows.filter { it.key.startsWith("${filter.name}:") }
        }

    // ── Key shape ─────────────────────────────────────────────────────────────

    /** The namespace [key] carries, i.e. the part before its `:`, or null when it carries none. */
    fun namespaceOf(key: String): String? {
        val colonIdx = key.indexOf(':')
        return if (colonIdx > 0) key.substring(0, colonIdx) else null
    }

    /** The dot-separated path of [key], namespace prefix removed. */
    fun keySegments(key: String): List<String> {
        val colonIdx = key.indexOf(':')
        val path = if (colonIdx > 0) key.substring(colonIdx + 1) else key
        return path.split('.')
    }

    // ── Cell states ───────────────────────────────────────────────────────────

    /** What a locale cell holding [raw] must say about itself. */
    fun valueStatus(raw: String): ValueStatus = when {
        raw.isEmpty() -> ValueStatus.MISSING
        raw.isBlank() -> ValueStatus.BLANK
        else -> ValueStatus.TRANSLATED
    }

    /** What the Usage column must say about a row whose [TranslationRow.usageCount] is [count]. */
    fun usageStatus(count: Int): UsageStatus = when (count) {
        DYNAMIC_USAGE -> UsageStatus.DYNAMIC
        in Int.MIN_VALUE..-1 -> UsageStatus.NOT_SCANNED
        0 -> UsageStatus.ORPHAN
        else -> UsageStatus.USED
    }

    // ── Columns ───────────────────────────────────────────────────────────────

    /**
     * The locales the table shows, i.e. [locales] minus [hidden], in the original order.
     * Hiding locales is what makes the table usable past four of them in a docked panel.
     */
    fun visibleLocales(locales: List<String>, hidden: Set<String>): List<String> =
        locales.filterNot { it in hidden }

    /**
     * The hidden set after the user toggles [locale] in the column menu.
     *
     * Hiding the last visible locale is refused: a table reduced to its Key and Usage columns
     * shows no translation at all, and nothing in the interface would explain why.
     */
    fun toggleLocale(locales: List<String>, hidden: Set<String>, locale: String): Set<String> {
        if (locale !in locales) return hidden
        if (locale in hidden) return hidden - locale
        if (visibleLocales(locales, hidden).size <= 1) return hidden
        return hidden + locale
    }

    /**
     * Preferred pixel widths for the whole table: the Key column, then one per locale, then Usage.
     *
     * The table used to run on `AUTO_RESIZE_ALL_COLUMNS`, which hands every column an equal share
     * of the viewport — so `common:navigation.menu.profile` got exactly as much room as `Usage`.
     * The key is the longest text in the table and is what the user scans, so it starts widest;
     * the columns stay draggable, and the table scrolls horizontally rather than crushing itself.
     */
    fun columnWidths(localeCount: Int): List<Int> =
        listOf(KEY_COLUMN_WIDTH) + List(localeCount) { LOCALE_COLUMN_WIDTH } + USAGE_COLUMN_WIDTH

    /**
     * Writes [value] for [key] in [locale], routing to the right translation file
     * by namespace and locale. Creates the entry when the locale does not have it yet.
     *
     * [moduleConfig] must be the one the displayed rows were loaded with: without it,
     * two modules owning a file with the same namespace and locale (the normal case in
     * a monorepo) would resolve to whichever comes first project-wide, and an edit made
     * in one module's table would land in the other module's file.
     *
     * Returns false when no matching translation file exists or the write fails
     * (e.g. read-only file) — the caller must then restore the previous cell value.
     * Must be called on the EDT.
     */
    fun saveValue(
        project: Project,
        key: String,
        locale: String,
        value: String,
        moduleConfig: ModuleConfig? = null,
    ): Boolean {
        val source = findSourceFor(project, key, locale, moduleConfig) ?: return false
        return try {
            DialogViewModel(project).saveTranslation(source, KeysSynchronizer().buildFullKey(key), value)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * The localization source holding [key]'s namespace for [locale], or null when the module
     * owns no such file.
     *
     * Shared by the in-place edit and by *Open translation file*: both have to land on the very
     * file the displayed row was read from, which is why [moduleConfig] is threaded through
     * rather than being resolved project-wide — see [saveValue] for what that costs in a monorepo.
     */
    internal fun findSourceFor(
        project: Project,
        key: String,
        locale: String,
        moduleConfig: ModuleConfig? = null,
    ): LocalizationSource? {
        val namespace = namespaceOf(key)
        return TranslationDataLoader.findSources(project, moduleConfig)
            .firstOrNull { source ->
                TranslationDataLoader.extractLocale(source) == locale &&
                    (namespace == null || TranslationDataLoader.extractNamespace(source) == namespace)
            }
    }

    /**
     * Counts usages of each key in the project's source files using PsiSearchHelper.
     * Returns a new list of rows with [TranslationRow.usageCount] filled in.
     *
     * This method performs PSI reads and must be called from a background thread.
     */
    fun countUsages(project: Project, rows: List<TranslationRow>): List<TranslationRow> {
        val config = Settings.getInstance(project).config()
        val searchScope = config.searchScope(project)
        val searchHelper = PsiSearchHelper.getInstance(project)

        val separators = Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator)
        val counted = rows.map { row ->
            val query = usageQuery(row.key, config.pluralSeparator)
            val accumulator = ReferencesAccumulator(
                query.bareKey,
                separators,
                // A key carrying no namespace lives in a default one, which is also what a
                // call site writing no namespace works under.
                query.namespace?.let { listOf(it) } ?: config.defaultNamespaces(),
            )

            for (word in query.words) {
                searchHelper.processElementsWithWord(
                    accumulator.process(),
                    searchScope,
                    word,
                    UsageSearchContext.ANY,
                    true
                )
            }
            // Distinct, because the same call site is reported once per word searched: a key
            // carrying a namespace matches both `navigation:menu.profile` and `menu.profile`,
            // and counting it twice inflated every usage of every prefixed key.
            row.copy(usageCount = accumulator.entries().distinct().size)
        }

        // Only what the text scan left at zero can be reached dynamically, and asking on
        // behalf of the whole batch is what keeps this to one search per distinct prefix.
        val orphanKeys = counted.filter { it.usageCount == 0 }.map { it.key }
        val reached = DynamicKeyUsages.reachedKeys(
            orphanKeys, searchScope, searchHelper, config.nsSeparator, config.keySeparator,
        )
        if (reached.isEmpty()) return counted
        return counted.map { row ->
            if (row.key in reached) row.copy(usageCount = DYNAMIC_USAGE) else row
        }
    }

    /** What [countUsages] searches the sources for, on behalf of one key. */
    internal data class UsageQuery(val bareKey: String, val words: List<String>, val namespace: String? = null)

    /**
     * The search terms standing for [key], and the prefix a hit has to start with.
     *
     * Two forms are searched: the full key (`navigation:menu.profile`), so an explicitly
     * namespaced call matches, and the bare one (`menu.profile`), for the implicit namespace
     * of `useTranslation('navigation') + t('menu.profile')`.
     *
     * Both are stripped of their plural suffix first. A key read from a translation file is a
     * *form* — `…addTrustee.description_other` — while the source only ever writes the key
     * i18next appends the suffix to, `t('…addTrustee.description', { count })`. Searching the
     * form found nothing by construction, so every pluralized key was reported as an orphan,
     * and *Cleanup unused keys* offered to delete a key that was in use.
     */
    internal fun usageQuery(key: String, pluralSeparator: String): UsageQuery {
        val colonIdx = key.indexOf(':')
        val namespace = if (colonIdx > 0) key.substring(0, colonIdx) else null
        val bareKey = PluralKey.stripSuffix(
            if (colonIdx > 0) key.substring(colonIdx + 1) else key,
            pluralSeparator,
        )
        return UsageQuery(
            bareKey,
            listOfNotNull(namespace?.let { "$it:$bareKey" }, bareKey),
            namespace,
        )
    }

    companion object {
        /**
         * [TranslationRow.usageCount] for a key no text search can find, but that a key built
         * at runtime reaches — see [UsageStatus.DYNAMIC].
         *
         * A sentinel rather than a field of its own, because the table model carries the count
         * itself into the *Usage* cell; `-1` already stands for "never scanned" there, and
         * adding a column the renderer would have to look up sideways buys nothing. Every
         * reading of it goes through [usageStatus].
         */
        internal const val DYNAMIC_USAGE = -2

        /** Widest by design: the key is the longest text of the table and the one users scan. */
        internal const val KEY_COLUMN_WIDTH = 320

        /** Enough for a short sentence; the column stays draggable from there. */
        internal const val LOCALE_COLUMN_WIDTH = 180

        /** A count and a word, no more. */
        internal const val USAGE_COLUMN_WIDTH = 110
    }
}
