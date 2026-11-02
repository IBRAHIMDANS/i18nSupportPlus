package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.format.PropertiesBundle
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Java resource bundles (`src/main/resources/messages_fr.properties`): files the locale guess cannot
 * place, the locale ending their name. They are declared here with the locale and the namespace
 * [PropertiesBundle.placementsIn] gives them, and read by [PropertiesLocalization]. The file without a
 * suffix takes the reference locale of the module holding its folder.
 *
 * The calls are not read yet: `bundle.getString("key")`, Spring's `getMessage("key", …)`.
 */
class PropertiesBundles : Technology {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        if (DumbService.isDumb(project)) return emptyList()
        val basePath = project.basePath.orEmpty()
        val config = Settings.getInstance(project).config()
        val excluded = PropertiesBundle.EXCLUDED_DIRECTORIES + config.excludedDirectorySet()
        val localization = Extensions.LOCALIZATION.extensionList.firstOrNull { it is PropertiesLocalization } ?: return emptyList()
        return runReadAction {
            FilenameIndex.getAllFilesByExt(project, PropertiesBundle.EXTENSION, GlobalSearchScope.projectScope(project))
                .filterNot { SourceDiscovery.isExcludedPath(it.path.removePrefix(basePath), excluded) }
                .groupBy { it.parent }
                .flatMap { (folder, files) ->
                    val module = ModuleSources.owner(config.modules, ModuleSources.FilePath.of(files.first().path, basePath))
                    val placements = PropertiesBundle.placementsIn(files.map { it.nameWithoutExtension }, ReferenceLocale.wanted(module, config))
                    files.sortedBy { it.name }.mapNotNull { file ->
                        val placement = placements[file.nameWithoutExtension] ?: return@mapNotNull null
                        val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
                        LocalizationSource(
                            localization.elementsTree(psi),
                            file.name,
                            folder.name,
                            displayPath(basePath, file),
                            localization,
                            locale = placement.locale,
                            namespace = placement.namespace,
                        )
                    }
                }
        }
    }

    private fun displayPath(basePath: String, file: VirtualFile) = pathToRoot(basePath, file.parent.path).trim('/') + '/' + file.name
}
