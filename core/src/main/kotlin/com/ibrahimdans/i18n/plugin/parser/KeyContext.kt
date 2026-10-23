package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.ide.settings.Config

/**
 * Everything [KeyResolver] reads besides the key itself: the settings, what the technologies
 * declare, and the key syntax of the module the key is written in.
 *
 * [keyScopes] and [fallbackNamespaces] are asked lazily, at most once: most keys need neither,
 * and answering them scans the project's translation files or framework configuration.
 *
 * @param config the settings the key is read with
 * @param keyScopes the scopes the technologies declare ([com.ibrahimdans.i18n.Technology.keyScopes])
 * @param fallbackNamespaces i18next's `fallbackNS`, in order
 * @param callerSyntax the key syntax of the module holding the caller's file, or null to use [config]'s separators
 */
class KeyContext(
    val config: Config,
    keyScopes: () -> Set<String> = { emptySet() },
    fallbackNamespaces: () -> List<String> = { emptyList() },
    val callerSyntax: KeySyntax? = null
) {
    val keyScopes: Set<String> by lazy(LazyThreadSafetyMode.NONE, keyScopes)
    val fallbackNamespaces: List<String> by lazy(LazyThreadSafetyMode.NONE, fallbackNamespaces)
}
