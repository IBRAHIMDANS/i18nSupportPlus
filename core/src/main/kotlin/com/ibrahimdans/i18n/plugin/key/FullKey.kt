package com.ibrahimdans.i18n.plugin.key

import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.nullableToList

/**
 * Represents translation key
 */
data class FullKey(
    val source: String,
    val ns: Literal?,
    val compositeKey:List<Literal>,
    val namespaces: List<String>? = null,
    val keyPrefix: List<Literal> = listOf(),
    val keyPrefixSource: String? = null,
    /**
     * i18next's `fallbackNS`: where the key is looked up once its own namespaces lack it. Set only
     * for a key whose namespaces come from its hook — a key writing its namespace is looked up there
     * alone, which keeps a typo in it visible.
     */
    val fallbackNamespaces: List<String> = listOf(),
) {
    /**
     * The namespaces the key is looked up in: the one written in the key, or else those its hook
     * declares (`useTranslation(['dashboard', 'common'])`).
     *
     * A written namespace replaces the hook's, as i18next does. Both used to be combined, so
     * `t('dashboardd:stats.count')` under `useTranslation(['dashboard'])` resolved in `dashboard`:
     * the typo got a green gutter icon and translated hints while i18next finds nothing at runtime.
     */
    fun allNamespaces(): List<String> = ns?.text.nullableToList().ifEmpty { namespaces.orEmpty() }

    /**
     * The namespaces to *read* the key from: [allNamespaces], then the [fallbackNamespaces] i18next
     * tries after them. For resolving, hints, completion and usage counts — never for writing, which
     * goes to the key's own namespace: creating a missing key must not add it to the fallback file.
     * A key without any namespace keeps the plugin's own lookup over every file.
     */
    fun lookupNamespaces(): List<String> {
        val own = allNamespaces()
        return if (own.isEmpty()) own else (own + fallbackNamespaces).distinct()
    }

    /**
     * True when a segment of the key is only known at runtime: a template expression
     * (`` t(`status.${kind}`) ``, normalized to a `${…}` literal) or a wildcard. Such a key
     * names no property that could be created — the gutter click on one wrote a property
     * literally called `${status}` into a translation file.
     */
    val isDynamic: Boolean
        get() = compositeKey.any { it.text == "*" || (it.text.startsWith("\${") && it.text.endsWith("}")) }
}