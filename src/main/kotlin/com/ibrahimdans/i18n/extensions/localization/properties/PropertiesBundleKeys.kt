package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The keys of the project's resource bundles, spelled as the tool window spells them. The code reads
 * them through `getString` / `getMessage`, which no search of the plugin sees yet: their usages
 * cannot be counted, and *Clean Up Keys…* must never offer them.
 *
 * Names no class of the Properties plugin: callable whether it is installed or not.
 */
object PropertiesBundleKeys {

    fun keys(project: Project): Set<String> = runReadAction {
        val sources = project.service<LocalizationSourceService>().findAllSources(project)
            .filter { it.tree?.value()?.containingFile?.virtualFile?.extension == PropertiesBundle.EXTENSION }
        if (sources.isEmpty()) emptySet() else TranslationDataLoader.translationsOf(project, sources).keys
    }
}
