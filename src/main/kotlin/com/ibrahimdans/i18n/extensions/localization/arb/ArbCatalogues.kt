package com.ibrahimdans.i18n.extensions.localization.arb

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.SourceDiscovery
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.utils.LocaleNames
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Flutter's ARB catalogues (`lib/l10n/app_en.arb`, `app_fr.arb`): JSON files the locale guess cannot
 * place, since neither `app_en` nor `l10n` is a locale. They are declared here with the locale they
 * hold, and read by the JSON format, which leaves their `@` metadata out (`JsonElementTree`).
 *
 * The locale is the catalogue's `@@locale` when it states one, as `flutter gen-l10n` reads it, else
 * the end of its name after the template's prefix (`app_pt_BR.arb` → `pt_BR`). Every catalogue holds
 * the default namespace: a Flutter app has a single catalogue per locale, its keys written without one.
 *
 * The calls are not read: `AppLocalizations.of(context)!.greeting` names its key as a getter of the
 * generated class, which the Dart analyzer already checks and completes.
 */
class ArbCatalogues : Technology {

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        if (DumbService.isDumb(project)) return emptyList()
        val basePath = project.basePath.orEmpty()
        return runReadAction { catalogues(project, basePath).mapNotNull { sourceOf(project, it, basePath) } }
    }

    private fun sourceOf(project: Project, virtualFile: VirtualFile, basePath: String): LocalizationSource? {
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? JsonFile ?: return null
        val locale = localeOf(virtualFile.nameWithoutExtension, declaredLocale(file)) ?: return null
        val localization = Extensions.LOCALIZATION.extensionList
            .firstOrNull { localization -> localization.types().any { it.languageFileType == file.fileType } } ?: return null
        return LocalizationSource(
            localization.elementsTree(file),
            file.name,
            virtualFile.parent.name,
            pathToRoot(basePath, virtualFile.parent.path).trim('/') + '/' + file.name,
            localization,
            locale = locale,
        )
    }

    /** The catalogue's `"@@locale": "fr"`, null when it states none. */
    private fun declaredLocale(file: JsonFile): String? =
        ((file.topLevelValue as? JsonObject)?.findProperty(LOCALE_PROPERTY)?.value as? JsonStringLiteral)?.value?.takeIf { it.isNotBlank() }

    companion object {

        private const val EXTENSION = "arb"
        private const val LOCALE_PROPERTY = "@@locale"

        /** Where `flutter` writes what it builds: catalogues copied there are no sources. */
        private val EXCLUDED = SourceDiscovery.DEFAULT_EXCLUDED_DIRECTORIES + ".dart_tool"

        fun isArb(file: VirtualFile?): Boolean = file?.extension == EXTENSION

        /**
         * The keys of the project's ARB catalogues. Flutter reads each as a getter of the generated
         * class, which no search of the plugin sees: their usages cannot be counted.
         */
        fun keys(project: Project): Set<String> = runReadAction {
            project.service<LocalizationSourceService>().findAllSources(project)
                .filter { isArb(it.tree?.value()?.containingFile?.virtualFile) }
                .flatMapTo(mutableSetOf()) { source -> source.tree?.entries().orEmpty().map { it.first } }
        }

        /**
         * The locale of a catalogue named [stem]: [declared] when it states one, else the end of the
         * name after an `_` (`app_en_US` → `en_US`), else the name itself when it is a locale (`fr`).
         */
        internal fun localeOf(stem: String, declared: String?): String? {
            declared?.let { return it }
            return stem.indices.filter { stem[it] == '_' }.map { stem.substring(it + 1) }.firstOrNull(LocaleNames::looksLikeLocale)
                ?: stem.takeIf(LocaleNames::looksLikeLocale)
        }

        private fun catalogues(project: Project, basePath: String): List<VirtualFile> =
            FilenameIndex.getAllFilesByExt(project, EXTENSION, GlobalSearchScope.projectScope(project))
                .filterNot { SourceDiscovery.isExcludedPath(it.path.removePrefix(basePath), EXCLUDED) }
                .sortedBy { it.path }
    }
}
