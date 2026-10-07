package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.tree.PluralKey

/** The keys of one translation file before and after a change, keyed by path (`[menu, home]`). */
data class FileVersions(
    val namespace: String,
    val locale: String,
    val before: Map<List<String>, String>,
    val after: Map<List<String>, String>,
)

/** One key of one locale added, removed or given another value. */
data class TranslationChange(
    val namespace: String,
    val path: List<String>,
    val locale: String,
    val kind: Kind,
    val before: String?,
    val after: String?,
) {
    enum class Kind { ADDED, REMOVED, MODIFIED }
}

/** A key added or changed in the reference locale that [locale] did not follow. */
data class LaggingLocale(val namespace: String, val path: List<String>, val locale: String)

/**
 * What a change does to the translations, key by key — the question a review asks, which the text
 * diff of a reordered or nested `fr.json` answers badly.
 *
 * Keys are compared as paths, never as `.`-joined strings: a flat key `"app.title"` and a nested
 * `app → title` stay apart. Reordering the keys of a file changes nothing here.
 */
object TranslationDiff {

    /** The changed keys of [files], sorted by namespace, key and locale. */
    fun changes(files: List<FileVersions>): List<TranslationChange> =
        files.flatMap { file ->
            (file.before.keys + file.after.keys).mapNotNull { path ->
                val before = file.before[path]
                val after = file.after[path]
                val kind = when {
                    before == null -> TranslationChange.Kind.ADDED
                    after == null -> TranslationChange.Kind.REMOVED
                    before != after -> TranslationChange.Kind.MODIFIED
                    else -> return@mapNotNull null
                }
                TranslationChange(file.namespace, path, file.locale, kind, before, after)
            }
        }.sortedWith(compareBy({ it.namespace }, { it.path.joinToString("\u0000") }, { it.locale }))

    /**
     * The locales of [localesByNamespace] that did not follow a key the [referenceLocale] added or
     * changed in [changes]. Plural forms count as one key: `item_one` changed in the reference and
     * `item_other` in `ja` is followed.
     */
    fun lagging(
        changes: List<TranslationChange>,
        referenceLocale: String,
        localesByNamespace: Map<String, Set<String>>,
        pluralSeparator: String,
    ): List<LaggingLocale> {
        fun base(path: List<String>) = path.dropLast(1) + PluralKey.stripSuffix(path.last(), pluralSeparator)
        val followed = changes.mapTo(HashSet()) { Triple(it.namespace, base(it.path), it.locale) }
        return changes
            .filter { it.locale == referenceLocale && it.kind != TranslationChange.Kind.REMOVED }
            .distinctBy { it.namespace to base(it.path) }
            .flatMap { change ->
                localesByNamespace[change.namespace].orEmpty()
                    .filter { it != referenceLocale && Triple(change.namespace, base(change.path), it) !in followed }
                    .sorted()
                    .map { LaggingLocale(change.namespace, change.path, it) }
            }
    }
}
