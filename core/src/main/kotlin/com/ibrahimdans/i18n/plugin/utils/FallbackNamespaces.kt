package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.I18nRegistry
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.ide.settings.Config

/**
 * i18next's `fallbackNS` for [Workspace]: the namespaces a key is looked up in once its own lack it,
 * in order. The settings come first, then what a technology of the registry reads from the
 * framework's own configuration — the settings are the user's word when both say something.
 */
object FallbackNamespaces {

    fun of(workspace: Workspace, config: Config, registry: I18nRegistry<*, *>): List<String> =
        (config.fallbackNamespaces() + registry.technologies().flatMap { it.fallbackNamespaces(workspace) }).distinct()
}
