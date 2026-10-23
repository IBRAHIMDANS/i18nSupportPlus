package com.ibrahimdans.i18n.extensions.technology

import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.TranslationTechnology
import com.ibrahimdans.i18n.Workspace

/**
 * Base class for i18n frameworks that don't require config-based source discovery.
 * Subclasses only need to provide translation function names.
 */
abstract class SimpleTechnology : TranslationTechnology<Nothing, Nothing> {
    override fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<Nothing, Nothing>> = emptyList()
    override fun initialize(workspace: Workspace) {}
    override fun cfgNamespaces(): List<String> = emptyList()
}
