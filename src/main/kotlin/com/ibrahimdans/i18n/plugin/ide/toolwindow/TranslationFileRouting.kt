package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config

/**
 * Which translation file a key spelled as [KeySpelling] spells it — `auth:login` or a bare
 * `menu.home` — is read from and written to, in a given locale.
 *
 * The loader strips the prefix of exactly the default namespaces, so a bare key lives in a
 * default namespace's file, and nowhere else. Four writers used to each carry their own copy of
 * the lookup, and three of them routed a bare key to the **first** file of the locale, whatever
 * its namespace: with `common` as the default namespace, the CSV import, the table's in-place
 * edit and *Fill Missing Translations* wrote `menu.home` into `fr/auth.json`, the first name in
 * alphabetical order, while *Clean Up Unused Keys* deleted it from every file of the locale,
 * an `auth` key of the same name included. One rule here, shared by all of them.
 */
object TranslationFileRouting {

    /** [fileFor] under [config]'s default namespaces. */
    fun fileFor(key: String, locale: String, sources: List<LocalizationSource>, config: Config): LocalizationSource? =
        fileFor(key, locale, sources, config.defaultNamespaces())

    /**
     * The file of [locale] among [sources] that [key] belongs to: the file of the namespace the key
     * is prefixed with, or, for a key spelled without one, the first file of a default namespace.
     *
     * A locale holding a single file keeps it as the fallback — the one-file-per-locale layout
     * names it after the locale, not the namespace. Several files and none of the wanted
     * namespace is null: the key is better skipped than written into a namespace it is not part of.
     */
    fun fileFor(key: String, locale: String, sources: List<LocalizationSource>, defaultNamespaces: List<String>): LocalizationSource? {
        val ofLocale = sources.filter { TranslationDataLoader.extractLocale(it) == locale }
        return filesOf(key, ofLocale, defaultNamespaces).firstOrNull() ?: ofLocale.singleOrNull()
    }

    /** [filesOf] under [config]'s default namespaces. */
    fun filesOf(key: String, sources: List<LocalizationSource>, config: Config): List<LocalizationSource> =
        filesOf(key, sources, config.defaultNamespaces())

    /**
     * The files of [sources], in every locale, that may hold [key]: its namespace's, or for a key
     * naming none the default namespaces' — never another namespace's file holding the same path.
     */
    fun filesOf(key: String, sources: List<LocalizationSource>, defaultNamespaces: List<String>): List<LocalizationSource> {
        val wanted = KeySpelling.namespaceOf(key)?.let { listOf(it) } ?: defaultNamespaces
        return sources.filter { TranslationDataLoader.extractNamespace(it, defaultNamespaces.first()) in wanted }
    }
}
