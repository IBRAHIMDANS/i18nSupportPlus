package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config

/**
 * How the tool window, *Sync Keys*, CSV export/import and the cleanup spell a key read from the
 * translation files, and how that spelling is taken apart again to write back.
 *
 * Both halves used to hard-code `.` between levels and split on it: a flat key (react-intl,
 * `flatKeys`) such as `"app.title"` came back as `[app, title]`, resolved to nothing, and an edit
 * *created* `"app": {"title": …}` next to it. A project nesting with another separator was split
 * on the wrong character. Levels are now joined and split with the configured key separator, and
 * a flat key is a single segment whatever it contains.
 *
 * The namespace keeps the `:` prefix: it is this spelling's own convention, the one the CSV format
 * exchanges, not the separator the code writes. A flat key carrying a `:` of its own and living in
 * a default namespace (a GetText msgid such as `Error: failed`) remains ambiguous under it.
 */
object KeySpelling {

    /** Separates a namespace from its key in this spelling. */
    const val NAMESPACE_SEPARATOR = ":"

    /** The separator levels are joined with, or null when keys are flat and never split. */
    private fun levelSeparator(config: Config): String? =
        config.keySeparator.takeUnless { config.usesFlatKeys() || it.isEmpty() }

    /** [path] extended by one level, [segment]. */
    fun child(config: Config, path: String, segment: String): String =
        if (path.isEmpty()) segment else path + (levelSeparator(config) ?: config.keySeparator) + segment

    /**
     * Laravel's separator inside a package's namespace (`courier::messages`), which a loaded key
     * spells `courier::messages:welcome`.
     */
    private const val PACKAGE_SEPARATOR = "::"

    /**
     * The namespace [key] is prefixed with, or null.
     *
     * Cut at the first `:`, except for a package namespace (`courier::messages:welcome`), whose
     * own `::` is not the end of the namespace: only that exact shape — `::` between two non-empty
     * names, followed by this spelling's `:` — is read whole, so an i18next `ns:key` is unchanged.
     */
    fun namespaceOf(key: String): String? {
        packageNamespaceOf(key)?.let { return it }
        val index = key.indexOf(NAMESPACE_SEPARATOR)
        return if (index > 0) key.substring(0, index) else null
    }

    private fun packageNamespaceOf(key: String): String? {
        val packageEnd = key.indexOf(PACKAGE_SEPARATOR)
        if (packageEnd <= 0 || key.indexOf(NAMESPACE_SEPARATOR) != packageEnd) return null
        val fileStart = packageEnd + PACKAGE_SEPARATOR.length
        val end = key.indexOf(NAMESPACE_SEPARATOR, fileStart)
        return if (end > fileStart) key.substring(0, end) else null
    }

    /** [key] without its namespace prefix. */
    fun pathOf(key: String): String =
        namespaceOf(key)?.let { key.substring(it.length + NAMESPACE_SEPARATOR.length) } ?: key

    /** The levels of [key]'s path: one segment for a flat key, split on the key separator otherwise. */
    fun segmentsOf(key: String, config: Config): List<String> {
        val path = pathOf(key)
        val separator = levelSeparator(config) ?: return listOf(path)
        return path.split(separator)
    }
}
