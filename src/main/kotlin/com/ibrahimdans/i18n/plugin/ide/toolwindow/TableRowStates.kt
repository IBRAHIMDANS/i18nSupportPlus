package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile

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
