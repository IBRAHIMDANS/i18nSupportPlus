package com.ibrahimdans.i18n.plugin.usages

/**
 * An inverted index from key path to the call sites under it, file by file: the structure behind
 * `i18n-check`'s `TreeSitterKeyUsageIndex` and the plugin's usage count, filled by each from its own
 * reader (tree-sitter, the PSI).
 *
 * A site is filed under its path and every path above it, so a key naming an object (`menu`) is
 * reached by the calls under it (`t('menu.home')`). A site with an empty path — a key built at
 * runtime — is kept with its file, under no path. Replacing a file's sites touches that file's
 * entries only.
 *
 * Not synchronized: its owner is.
 */
class CallSiteIndex<S : CallSiteIndex.Site> {

    /** One place the code names a key, by the segments of the [path] it writes. */
    interface Site {
        val path: List<String>
    }

    /** The sites of each indexed file. */
    private val files = mutableMapOf<String, List<S>>()

    /** The sites under each key path, the paths above it included. */
    private val byPath = mutableMapOf<List<String>, MutableSet<S>>()

    /** The files indexed. */
    val paths: Set<String> get() = files.keys

    /** Replaces the sites of the file at [path] with [sites]. */
    fun update(path: String, sites: List<S>) {
        remove(path)
        files[path] = sites
        for (site in sites) for (depth in 1..site.path.size) {
            byPath.getOrPut(site.path.subList(0, depth)) { mutableSetOf() } += site
        }
    }

    /** Drops the file at [path], if it was indexed. */
    fun remove(path: String) {
        val sites = files.remove(path) ?: return
        for (site in sites) for (depth in 1..site.path.size) {
            val prefix = site.path.subList(0, depth)
            val left = byPath[prefix] ?: continue
            left -= site
            if (left.isEmpty()) byPath -= prefix
        }
    }

    /** Drops every file. */
    fun clear() {
        files.clear()
        byPath.clear()
    }

    /** The sites whose path is [path] or starts with it. */
    fun under(path: List<String>): Set<S> = byPath[path].orEmpty()

    /** The sites whose path is exactly [path]. */
    fun at(path: List<String>): List<S> = under(path).filter { it.path == path }

    /** Every site of every file. */
    fun sites(): Sequence<S> = files.values.asSequence().flatten()
}
