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
    val keyPrefixSource: String? = null
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
}