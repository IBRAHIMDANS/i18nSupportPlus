package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.tree.Tree

/**
 * Describes localization source.
 * May be root of json, yaml file, js object
 *
 * [N] is the node type of [tree] — a PSI element in the plugin, a parsed node outside it — and
 * [F] the format that read the file, which the plugin needs back to write into it.
 *
 * [locale] and [namespace] are set when something states them — a module's path template — and
 * take precedence over the guess made from [name] and [parent]. A [locale] with no [namespace] is a
 * file named after its locale, holding the default namespace.
 */
data class TranslationSource<N, out F : TranslationFormat>(
    val tree: Tree<N>?,
    val name: String,
    val parent: String,
    val displayPath: String,
    val localization: F,
    val host: N? = null,
    val locale: String? = null,
    val namespace: String? = null
)
