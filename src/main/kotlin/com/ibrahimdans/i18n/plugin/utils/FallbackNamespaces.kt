package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.project.Project

/**
 * i18next's `fallbackNS` for [project]: the namespaces a key is looked up in once its own lack it,
 * in order. The settings come first, then what a technology reads from the framework's own
 * configuration — the settings are the user's word when both say something.
 */
object FallbackNamespaces {

    fun of(project: Project, config: Config): List<String> =
        (config.fallbackNamespaces() + Extensions.TECHNOLOGY.extensionList.flatMap { it.fallbackNamespaces(project) }).distinct()
}
