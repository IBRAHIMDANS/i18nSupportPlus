package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.localization.arb.ArbCatalogues
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.inspection.DuplicateTranslationValueInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.KeepList
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a single row in the translation table.
 *
 * @param usageCount Number of usages found in source code.
 *   -3 = kept by a rule of the project's keep list ([TableViewModel.KEPT_USAGE]),
 *   -2 = reached only through a runtime-built key ([TableViewModel.DYNAMIC_USAGE]),
 *   -1 = not yet scanned, 0 = orphan (unused), ≥1 = used.
 */
data class TranslationRow(val key: String, val values: Map<String, String>, val usageCount: Int = -1)

/**
 * The text shown for this filter under [config], for keys read from [sources]. Only
 * [NamespaceFilter.Default] cares: where every source is one file per locale (Paraglide's
 * `messages/en.json`, next-intl, vue-i18n's `locales/en.json`), no key has a namespace, and
 * naming the configured default — `translation (default)` — pointed at a setting the project
 * never uses.
 */
fun NamespaceFilter.label(config: Config, sources: List<LocalizationSource>): String =
    if (this == NamespaceFilter.Default && !carriesNamespaces(sources)) PluginBundle.message("toolwindow.table.namespace.none")
    else label(config)

/**
 * False only when every source of [sources] is a file named after its locale alone; no source at all
 * says nothing about the project, and keeps the configured name.
 */
internal fun carriesNamespaces(sources: List<LocalizationSource>): Boolean =
    sources.isEmpty() || sources.any { !it.isLocaleNamedFile() }

/**
 * What the status combo filters on: "what is left to translate" without a trip through the
 * Stats tab's popup.
 *
 * Built like [NamespaceFilter]: the entry is the identity the filter compares, and [label] is
 * read by the combo's renderer only — translating a label can never change what a filter keeps.
 */
enum class StatusFilter(private val labelKey: String) {
    /** No filtering. */
    ALL("toolwindow.table.status.all"),

    /** Keys with no entry in at least one *shown* locale. */
    MISSING("toolwindow.table.status.missing"),

    /** Keys whose entry is blank in at least one *shown* locale. */
    BLANK("toolwindow.table.status.blank"),

    /** Keys the last scan found used nowhere; meaningless before a scan, see [TableViewModel.isStatusFilterAvailable]. */
    ORPHAN("toolwindow.table.status.orphan"),

    /** Keys whose value, in a *shown* locale, another key of the same namespace holds too. */
    DUPLICATE("toolwindow.table.status.duplicate"),

    /**
     * Keys someone has to review in a *shown* locale: marked to review, approved then changed,
     * corrected after a change request, or decided on a source text changed since. Offered only
     * where reviews are on, see [TableReviews].
     */
    TO_REVIEW("toolwindow.table.status.to_review"),

    /**
     * Keys whose value, in a *shown* locale, is still the one decided on while the reference text
     * changed since — the translations to translate again. A subset of [TO_REVIEW].
     */
    SOURCE_CHANGED("toolwindow.table.status.source_changed"),

    /** Keys whose value, in a *shown* locale, a reviewer asked to correct and nobody has touched since. */
    CHANGES_REQUESTED("toolwindow.table.status.changes_requested");

    /** The text shown in the combo. Never compared against anything. */
    val label: String get() = PluginBundle.message(labelKey)

    /** True for the filters that read reviews, meaningless where reviews are off. */
    val readsReviews: Boolean get() = this == TO_REVIEW || this == SOURCE_CHANGED || this == CHANGES_REQUESTED
}

/**
 * The reviews of the table's module, by locale, as `.i18n-reviews/<locale>.json` holds them.
 *
 * [OFF] where the module has no reviews folder: no cell shows a review, the status combo does not
 * offer the review filters and the context menu adds nothing — a team that never asked for reviews
 * sees the table it always had. Pure: the panel reads the store, the filters and the renderer read
 * this, so both are tested without a project.
 *
 * A review is read against the source text its key holds now in [referenceLocale], so that a value
 * decided on an older source shows as [ShownReview.SOURCE_CHANGED]. The source is passed in by the
 * caller, read from the row whether its column is shown or not; its hash is kept per key, so the
 * renderer does not hash it again for every cell it paints.
 */
class TableReviews(
    private val byLocale: Map<String, Map<ReviewKey, Review>>,
    /** The namespace a key spelled without one lives in: `Config.defaultNamespaces().first()`. */
    private val defaultNamespace: String,
    /** False for [OFF]: reviews are not on for the module. */
    val enabled: Boolean = true,
    /**
     * The module's reference locale, as `ReferenceLocale.of` picks it among the loaded locales —
     * the locale a decision records its source in. Null when none matches: no source is known then.
     */
    val referenceLocale: String? = null,
    /** The module's `Config.pluralSeparator`: how a legacy numeric plural form is spelled. */
    val pluralSeparator: String = Config().pluralSeparator,
    /**
     * The row of a key, among the rows loaded now: where a plural form the reference lacks finds its
     * `other` form ([ReviewSources]). Read at each call, so that it follows the rows of the day.
     */
    private val rowOf: (String) -> TranslationRow? = { null },
) {
    /** Per key, the source text last hashed and its [ReviewSource]: one hash per key and text. */
    private val sources = ConcurrentHashMap<String, Pair<String, ReviewSource>>()

    /** The review of [key] (spelled as the table spells it) in [locale], or null. */
    fun reviewOf(key: String, locale: String): Review? =
        if (!enabled) null else byLocale[locale]?.get(reviewKey(key))

    /** The review file's name for [key], spelled as the table spells it. */
    fun reviewKey(key: String): ReviewKey = ReviewKey.fromSpelling(key, defaultNamespace)

    /**
     * What the review of [key] in [locale] means for [raw], the cell's value — an empty cell has no
     * entry, hence no value anyone reviewed (see [shown]) — given [source], the text [key] holds now
     * in [referenceLocale]. The reference locale itself has no source: it is never
     * [ShownReview.SOURCE_CHANGED].
     */
    fun shownOf(key: String, locale: String, raw: String?, source: String? = null): ShownReview {
        val review = reviewOf(key, locale) ?: return ShownReview.NONE
        val current = if (locale == referenceLocale) null else sourceOf(key, source)
        return shown(review, raw?.takeIf { it.isNotEmpty() }, current)
    }

    /** What the review of [row]'s key in [locale] means for its value, against its own source. */
    fun shownOf(row: TranslationRow, locale: String): ShownReview =
        shownOf(row.key, locale, row.values[locale], sourceText(row))

    /**
     * The text [row] is translated from in [referenceLocale], whatever locales are shown: its own, or
     * its plural group's `other` form's when the reference lacks its category ([ReviewSources]); null
     * when it has none.
     */
    fun sourceText(row: TranslationRow?): String? {
        val locale = referenceLocale ?: return null
        row ?: return null
        return ReviewSources.sourceTextOf(row.key, pluralSeparator) { key ->
            (if (key == row.key) row else rowOf(key))?.values?.get(locale)
        }
    }

    /**
     * [text], the source [key] holds now, as a [ReviewSource] in [referenceLocale]; null when either
     * is unknown or [text] is empty. Hashed once per key and text: an edit of the source is seen.
     */
    fun sourceOf(key: String, text: String?): ReviewSource? {
        val locale = referenceLocale ?: return null
        if (!enabled || text.isNullOrEmpty()) return null
        sources[key]?.let { (hashed, source) -> if (hashed == text) return source }
        return ReviewSource.of(locale, text).also { sources[key] = text to it }
    }

    companion object {
        val OFF = TableReviews(emptyMap(), "", enabled = false)

        /** What the *To review* filter keeps: no approval holds for the value shown, and no request is pending. */
        val TO_REVIEW_STATES = setOf(
            ShownReview.TO_REVIEW, ShownReview.CHANGED_SINCE_APPROVAL, ShownReview.FIXED_SINCE_REQUEST, ShownReview.SOURCE_CHANGED,
        )
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
enum class UsageStatus { NOT_SCANNED, ORPHAN, DYNAMIC, KEPT, USED }

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

    /**
     * Returns the rows [filter] selects, judging values in [shownLocales] only.
     *
     * A hidden locale does not hold a row back: hiding `de` is how a user says "not my concern
     * right now", and *Missing* answering with rows whose every shown cell is filled would point
     * at nothing visible. [StatusFilter.ORPHAN] keeps the rows the scan found unused — none
     * before a scan, since "not scanned" is not "unused". [StatusFilter.DUPLICATE] compares the
     * values of one locale and one namespace at a time — one translation file, as the
     * *Duplicate translation value* inspection does. [StatusFilter.TO_REVIEW],
     * [StatusFilter.SOURCE_CHANGED] and [StatusFilter.CHANGES_REQUESTED] read [reviews] against the
     * value each shown cell holds now and the row's source, read in the reference locale even when
     * its column is hidden: an approval of an older value, or on an older source, reads as *to review*.
     */
    fun filterByStatus(
        filter: StatusFilter,
        rows: List<TranslationRow>,
        shownLocales: List<String>,
        reviews: TableReviews = TableReviews.OFF,
    ): List<TranslationRow> {
        fun anyShown(status: ValueStatus) = { row: TranslationRow ->
            shownLocales.any { valueStatus(row.values[it] ?: "") == status }
        }
        fun anyReview(states: Set<ShownReview>) = { row: TranslationRow ->
            shownLocales.any { reviews.shownOf(row, it) in states }
        }
        return when (filter) {
            StatusFilter.ALL -> rows
            StatusFilter.MISSING -> rows.filter(anyShown(ValueStatus.MISSING))
            StatusFilter.BLANK -> rows.filter(anyShown(ValueStatus.BLANK))
            StatusFilter.ORPHAN -> rows.filter { usageStatus(it.usageCount) == UsageStatus.ORPHAN }
            StatusFilter.DUPLICATE -> {
                val duplicated = duplicatedKeys(rows, shownLocales)
                rows.filter { it.key in duplicated }
            }
            StatusFilter.TO_REVIEW -> rows.filter(anyReview(TableReviews.TO_REVIEW_STATES))
            StatusFilter.SOURCE_CHANGED -> rows.filter(anyReview(setOf(ShownReview.SOURCE_CHANGED)))
            StatusFilter.CHANGES_REQUESTED -> rows.filter(anyReview(setOf(ShownReview.CHANGES_REQUESTED)))
        }
    }

    /** The entries the status combo offers: the review filters only where [reviews] are on. */
    fun statusFilters(reviews: TableReviews): List<StatusFilter> =
        StatusFilter.entries.filter { reviews.enabled || !it.readsReviews }

    private fun duplicatedKeys(rows: List<TranslationRow>, shownLocales: List<String>): Set<String> {
        val byNamespace = rows.groupBy { namespaceOf(it.key) }.values
        return shownLocales.flatMapTo(mutableSetOf()) { locale ->
            byNamespace.flatMap { namespaceRows ->
                DuplicateTranslationValueInspection.duplicated(namespaceRows.mapNotNull { row -> row.values[locale]?.let { it to row.key } })
            }
        }
    }

    /**
     * Whether [filter] can be picked for [rows]. Only [StatusFilter.ORPHAN] can be refused:
     * before any key was scanned it would show an empty table that reads as "nothing unused",
     * which is exactly what nobody knows yet.
     */
    fun isStatusFilterAvailable(filter: StatusFilter, rows: List<TranslationRow>): Boolean =
        filter != StatusFilter.ORPHAN || rows.any { usageStatus(it.usageCount) != UsageStatus.NOT_SCANNED }

    /**
     * The rows the table shows: [rows] narrowed by namespace, then by status over [shownLocales],
     * then by the text [query]. One entry point, so the panel cannot apply the three in an order
     * the tests never saw.
     */
    fun visibleRows(
        rows: List<TranslationRow>,
        query: String,
        namespace: NamespaceFilter,
        status: StatusFilter,
        shownLocales: List<String>,
        reviews: TableReviews = TableReviews.OFF,
    ): List<TranslationRow> =
        filter(query, filterByStatus(status, filterByNamespace(namespace, rows), shownLocales, reviews))

    /**
     * The reviews of [locales] under [root], or [TableReviews.OFF] when there is no root or it has
     * no reviews folder, read against [moduleConfig]'s reference locale ([ReferenceLocale.of], the
     * choice every decision records its source with). [rowOf] finds a key's row among those loaded,
     * where a plural form reads its `other` form's source. Reads files: off the EDT, or on it for a
     * reload (the store caches them).
     */
    fun loadReviews(
        project: Project, root: VirtualFile?, locales: List<String>, config: Config, moduleConfig: ModuleConfig? = null,
        rowOf: (String) -> TranslationRow? = { null },
    ): TableReviews {
        if (root == null) return TableReviews.OFF
        val store = ReviewStore.getInstance(project)
        if (!store.isEnabled(root)) return TableReviews.OFF
        return TableReviews(
            locales.associateWith { store.reviews(root, it) }, config.defaultNamespaces().first(),
            referenceLocale = ReferenceLocale.of(moduleConfig, config, locales), pluralSeparator = config.pluralSeparator, rowOf = rowOf,
        )
    }

    /** Where [moduleConfig]'s reviews folder hangs, see [ReviewLocation.rootOf]. */
    fun reviewRoot(project: Project, moduleConfig: ModuleConfig?): VirtualFile? = ReviewLocation.rootOf(project, moduleConfig)

    // ── Key shape ─────────────────────────────────────────────────────────────

    /** The namespace [key] carries, i.e. the part before its `:`, or null when it carries none. */
    fun namespaceOf(key: String): String? = KeySpelling.namespaceOf(key)

    /**
     * Whether the table lays out a Namespace column in front of the key.
     *
     * Only when the rows shown can come from more than one namespace: [filter] is
     * [NamespaceFilter.All] and [filters] — the combo's entries, `All` included — offers more
     * than one group. Under a single namespace the column would repeat one word on every row,
     * and a project without namespaces has nothing to put in it at all.
     */
    fun showsNamespaceColumn(filter: NamespaceFilter, filters: List<NamespaceFilter>): Boolean =
        filter == NamespaceFilter.All && filters.size > 2

    /** What the Namespace column says for [key]: its namespace, or the default group's label under [config]. */
    fun namespaceLabel(key: String, config: Config = Config()): String =
        namespaceOf(key)?.let { NamespaceFilter.Named(it).label } ?: NamespaceFilter.Default.label(config)

    /**
     * How the Namespace column sorts: the default group's label first — the tree, the combo
     * and the Stats lead with it too — then the names alphabetically.
     */
    fun namespaceOrder(config: Config): Comparator<String> {
        val default = NamespaceFilter.Default.label(config)
        return compareBy<String>({ it != default }, { it })
    }

    /** The key as the Key column shows it next to a Namespace column: without its prefix. */
    fun keyLabel(key: String): String = KeySpelling.pathOf(key)

    /** The levels of [key]'s path, namespace prefix removed — a single one when keys are flat. */
    fun keySegments(key: String, config: Config = Config()): List<String> = KeySpelling.segmentsOf(key, config)

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
        KEPT_USAGE -> UsageStatus.KEPT
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
     * The part of the saved [hidden] set that applies to [locales], the locales loaded now.
     *
     * A locale absent from the project is dropped here, for this load only — the caller must not
     * write the result back, or a locale missing for one reload would lose its hidden state for
     * good. A saved set covering every loaded locale is ignored altogether: [toggleLocale] never
     * lets the user build one, but locales can disappear from under a saved set, and a table
     * left with no locale column would not say why.
     */
    fun hiddenAmong(locales: List<String>, hidden: Set<String>): Set<String> {
        val applicable = hidden.filter { it in locales }.toSet()
        return if (locales.isNotEmpty() && visibleLocales(locales, applicable).isEmpty()) emptySet() else applicable
    }

    /**
     * Preferred pixel widths for the whole table: the Namespace column when [withNamespace],
     * the Key column, then one per locale, then Usage.
     *
     * The table used to run on `AUTO_RESIZE_ALL_COLUMNS`, which hands every column an equal share
     * of the viewport — so `common:navigation.menu.profile` got exactly as much room as `Usage`.
     * The key is the longest text in the table and is what the user scans, so it starts widest;
     * the columns stay draggable, and the table scrolls horizontally rather than crushing itself.
     */
    fun columnWidths(localeCount: Int, withNamespace: Boolean = false): List<Int> =
        listOfNotNull(NAMESPACE_COLUMN_WIDTH.takeIf { withNamespace }, KEY_COLUMN_WIDTH) +
            List(localeCount) { LOCALE_COLUMN_WIDTH } + USAGE_COLUMN_WIDTH

    /** The table header, in the same order as [columnWidths]: Namespace when [withNamespace], Key, the locales, Usage. */
    fun columnNames(locales: List<String>, withNamespace: Boolean = false): List<String> =
        listOfNotNull(
            PluginBundle.message("toolwindow.table.column.namespace").takeIf { withNamespace },
            PluginBundle.message("toolwindow.table.column.key"),
        ) + locales + PluginBundle.message("toolwindow.table.column.usage")

    /**
     * The cells of [row], column for column with [columnNames]. The key cell holds the full key —
     * every row action reads it from there. The usage cell holds the count itself, not a rendered
     * string: the renderer decides how it reads, and the context menu does not sniff a label.
     */
    fun rowCells(
        row: TranslationRow,
        locales: List<String>,
        withNamespace: Boolean = false,
        config: Config = Config(),
    ): List<Any> =
        listOfNotNull<Any>(namespaceLabel(row.key, config).takeIf { withNamespace }, row.key) +
            locales.map { row.values[it] ?: "" } + row.usageCount

    /**
     * [fresh] rows, each carrying the [TranslationRow.usageCount] [previous] already knew for
     * the same key.
     *
     * A reload follows every change to a translation file — the table's own in-place edits
     * included — and reloaded rows start back at "not scanned": one corrected value used to
     * throw away a scan that is expensive on a large project. The count depends on the source
     * code, not on the value, so a key whose value changed keeps it. A key [previous] did not
     * have stays not scanned, and a key gone from [fresh] simply disappears. Values and order
     * are those of [fresh].
     */
    fun mergeUsages(fresh: List<TranslationRow>, previous: List<TranslationRow>): List<TranslationRow> {
        val known = previous.associate { it.key to it.usageCount }
        return fresh.map { row -> known[row.key]?.let { row.copy(usageCount = it) } ?: row }
    }

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
            DialogViewModel(project).saveTranslation(
                source, KeysSynchronizer().buildFullKey(key, Settings.getInstance(project).config()), value
            )
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
     * The file and offset where [key] is declared for [locale]. Falls back to the deepest
     * segment that does resolve — a key present in one locale and not in another still opens
     * the right file, at its nearest parent, rather than nothing at all.
     *
     * Shared by *Open translation file* (F4) in the table and by the Translations tab of Search
     * Everywhere: both open the very file their row or hit was read from, hence [moduleConfig].
     * Must be called inside a read action.
     */
    internal fun locate(
        project: Project,
        key: String,
        locale: String,
        moduleConfig: ModuleConfig? = null,
    ): Pair<VirtualFile, Int>? {
        val source = findSourceFor(project, key, locale, moduleConfig) ?: return null
        var node: Tree<PsiElement> = source.tree ?: return null
        for (segment in keySegments(key, Settings.getInstance(project).config())) {
            node = node.findChild(segment) ?: break
        }
        val psi = node.value()
        val file = psi.containingFile?.virtualFile ?: return null
        return file to psi.textOffset
    }

    /**
     * Counts usages of each key in the project's source files through [IdeKeyUsageIndex], in
     * [moduleConfig]'s code only when given — the module the rows were loaded from.
     * Returns a new list of rows with [TranslationRow.usageCount] filled in.
     *
     * This method performs PSI reads and must be called from a background thread.
     */
    fun countUsages(project: Project, rows: List<TranslationRow>, moduleConfig: ModuleConfig? = null): List<TranslationRow> {
        val config = Settings.getInstance(project).config()
        val index = IdeKeyUsageIndex(project, moduleConfig)
        val counts = index.usageCounts(rows.map { it.key })
        // Flutter reads an ARB key as a getter of its generated class, which no search here sees:
        // such a key stays unscanned rather than reported unused, and *Cleanup* never offers it.
        val arbKeys = ArbCatalogues.keys(project)
        val withPrefixed = rows.map { row ->
            val count = counts[row.key] ?: 0
            row.copy(usageCount = if (count == 0 && row.key in arbKeys) NOT_SCANNED else count)
        }

        // A key the project keeps is used by declaration, whatever the code shows: no search for it.
        val keepList = KeepList.of(config)
        val withKept = if (keepList.isEmpty()) withPrefixed
            else withPrefixed.map { row -> if (row.usageCount == 0 && keepList.matches(row.key)) row.copy(usageCount = KEPT_USAGE) else row }

        // Only what the text scan left at zero can be reached dynamically, and asking on
        // behalf of the whole batch is what keeps this to one search per distinct prefix.
        val orphanKeys = withKept.filter { it.usageCount == 0 }.map { it.key }
        val reached = index.dynamicallyReached(orphanKeys)
        if (reached.isEmpty()) return withKept
        return withKept.map { row ->
            if (row.key in reached) row.copy(usageCount = DYNAMIC_USAGE) else row
        }
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
        /** [TranslationRow.usageCount] for a key whose usages cannot be searched, as before any scan. */
        internal const val NOT_SCANNED = -1
        internal const val KEPT_USAGE = -3

        /** Namespaces are short words (`common`, `deposit-box`): narrower than a locale value. */
        internal const val NAMESPACE_COLUMN_WIDTH = 120

        /** Widest by design: the key is the longest text of the table and the one users scan. */
        internal const val KEY_COLUMN_WIDTH = 320

        /** Enough for a short sentence; the column stays draggable from there. */
        internal const val LOCALE_COLUMN_WIDTH = 180

        /** A count and a word, no more. */
        internal const val USAGE_COLUMN_WIDTH = 110
    }
}
