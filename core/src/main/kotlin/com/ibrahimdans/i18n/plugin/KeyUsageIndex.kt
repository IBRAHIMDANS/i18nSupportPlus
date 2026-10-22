package com.ibrahimdans.i18n.plugin

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * Where the code uses the keys of the translation files, asked by key rather than by element, so
 * that a reader without the IDE's search can answer it too.
 *
 * Keys are written the way the code names them: `common:menu.home`, a plural form such as
 * `cart.item_other` standing for the key the code writes (`cart.item`).
 */
interface KeyUsageIndex {

    /**
     * The places using [key]: a call site naming it, with its namespace or under a hook declaring
     * it, a call under a hook's key prefix writing only its last levels, and a translation value
     * linking to it (vue-i18n's `@:key`). Each place once, whatever number of ways it was found.
     */
    fun usages(key: String): List<KeyUsage>

    /**
     * The subset of [keys] some key the code builds at runtime may produce: `` t(`status.${'$'}{kind}`) ``
     * reaches every `status.*` key. Asked of a batch, as the search runs once per shared prefix.
     */
    fun dynamicallyReached(keys: Collection<String>): Set<String>
}

/** One place using a key: the file at [path] and the range of the literal naming it. */
data class KeyUsage(val path: String, val span: TextSpan)
