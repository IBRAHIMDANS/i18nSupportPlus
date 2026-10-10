package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * The usages of a key that leave no reference on it, found by searching the code for the key's
 * words: a call under a hook's namespace or key prefix, a `{ ns }` option. Shared by the orphan
 * scan behind *Scan Orphans* and *Cleanup Unused Keys*, and by *Unused translation key* and the
 * usage count above each key, so that all of them agree on what "used" means.
 */
internal object IndirectKeyUsages {

    /**
     * How many call sites name [key] outright, found by a text search of the key rather than by
     * references: `t('menu.profile')` under `useTranslation('navigation')`, or
     * `t('label', { ns: 'other' })`, both of which leave no reference on the key itself.
     */
    fun textCount(key: String, config: Config, searchScope: GlobalSearchScope, searchHelper: PsiSearchHelper): Int {
        val query = usageQuery(key, config.pluralSeparator)
        val accumulator = ReferencesAccumulator(
            query.bareKey,
            Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
            // A key carrying no namespace lives in a default one, which is also what a
            // call site writing no namespace works under.
            query.namespace?.let { listOf(it) } ?: config.defaultNamespaces(),
        )
        for (word in query.words) {
            searchHelper.processElementsWithWord(accumulator.process(), searchScope, word, UsageSearchContext.ANY, true)
        }
        // Distinct, because the same call site is reported once per word searched: a key
        // carrying a namespace matches both `navigation:menu.profile` and `menu.profile`,
        // and counting it twice inflated every usage of every prefixed key.
        return accumulator.entries().distinct().size
    }

    /**
     * Usages of [keys] through a hook key prefix — react-i18next's `keyPrefix`, next-intl's
     * `useTranslations('Home')` — keyed by the keys found, absent ones left out.
     *
     * The call site writes only the key's last levels (`t('title')` for `header.title`), so the
     * last segment is searched as a word, and a literal counts only when the key it resolves to —
     * extracted and parsed the way the annotator does, prefix applied — has exactly this path and
     * works in this key's namespace. Literals without a prefix are left to the text scan, which
     * already counted them ([textCount]): counting them here too would double every usage.
     */
    fun prefixedCounts(project: Project, keys: List<String>, config: Config): Map<String, Int> {
        if (keys.isEmpty()) return emptyMap()
        val defaultNamespaces = config.defaultNamespaces()
        val languages = Extensions.LANG.extensionList
        val parser = RawKeyParser(project)
        val found = mutableMapOf<String, MutableSet<PsiElement>>()

        val byWord = keys.groupBy { key ->
            KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config).last()
        }
        for ((word, wordKeys) in byWord) {
            if (word.isBlank()) continue
            val wanted = wordKeys.associateWith { key ->
                KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config)
            }
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                { element, _ ->
                    val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) } ?: return@processElementsWithWord true
                    val fullKey = languages.firstNotNullOfOrNull { it.extractRawKey(literal) }
                        ?.let { parser.parse(it) }
                        ?.takeIf { it.keyPrefix.isNotEmpty() }
                        ?: return@processElementsWithWord true
                    val path = fullKey.compositeKey.map { it.text }
                    val namespaces = fullKey.allNamespaces()
                    for ((key, segments) in wanted) {
                        if (path != segments) continue
                        val namespace = KeySpelling.namespaceOf(key)
                        val inNamespace = if (namespace == null) namespaces.isEmpty() || namespaces.any { it in defaultNamespaces }
                            else namespaces.isEmpty() || namespace in namespaces
                        if (inNamespace) found.getOrPut(key) { mutableSetOf() } += literal
                    }
                    true
                },
                config.searchScope(project),
                word,
                UsageSearchContext.ANY,
                true
            )
        }
        return found.mapValues { it.value.size }
    }

    /** What [textCount] searches the sources for, on behalf of one key. */
    data class UsageQuery(val bareKey: String, val words: List<String>, val namespace: String? = null)

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
    fun usageQuery(key: String, pluralSeparator: String): UsageQuery {
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
}
