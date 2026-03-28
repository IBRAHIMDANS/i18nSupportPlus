package com.ibrahimdans.i18n.extensions.technology

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.intellij.openapi.project.Project

/**
 * Base class for i18n frameworks that don't require config-based source discovery.
 * Subclasses only need to provide translation function names.
 */
abstract class SimpleTechnology : Technology {
    override fun findSourcesByConfiguration(project: Project): List<LocalizationSource> = emptyList()
    override fun initialize(project: Project) {}
    override fun cfgNamespaces(): List<String> = emptyList()
}
