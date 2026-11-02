package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.usageScope
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor

/**
 * How the code uses the keys of the project's resource bundles. The code reads them through
 * `getString` / `getMessage`, `@PropertyKey` parameters or Thymeleaf's `#{key}`, which the plugin's
 * own text scan does not see: the platform does, as references to each key's property. So a key is
 * counted by a reference search on its properties, in every locale — the same call resolves to all of
 * them, and is counted once.
 *
 * Names no class of the Properties plugin outside a read of its files: callable whether it is installed or not.
 */
object PropertiesBundleKeys {

    /**
     * Each key of the project's resource bundles, spelled as the tool window spells it, with the number
     * of places in the code referring to it — zero for a key nothing reads. In a monorepo, only the code
     * of the module owning the bundle counts ([ModuleSources.usageScope]).
     */
    fun usageCounts(project: Project): Map<String, Int> = runReadAction {
        val sources = project.service<LocalizationSourceService>().findAllSources(project)
            .filter { it.tree?.value()?.containingFile?.virtualFile?.extension == PropertiesBundle.EXTENSION }
        if (sources.isEmpty()) return@runReadAction emptyMap()
        val defaultNamespaces = Settings.getInstance(project).config().defaultNamespaces()
        val referring = LinkedHashMap<String, MutableSet<PsiElement>>()
        for (source in sources) {
            val file = source.tree?.value()?.containingFile as? PropertiesFile ?: continue
            // The prefix the tool window gives the source's keys (TranslationDataLoader.translationsOf).
            val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespaces.first())
            val prefix = if (namespace in defaultNamespaces) "" else namespace + KeySpelling.NAMESPACE_SEPARATOR
            val scope = ModuleSources.usageScope(project, file.virtualFile)
            for (property in file.properties) {
                val key = property.unescapedKey ?: continue
                val found = referring.getOrPut(prefix + key) { HashSet() }
                ReferencesSearch.search(property.psiElement, scope).forEach(Processor { found += it.element; true })
            }
        }
        referring.mapValues { it.value.size }
    }
}
