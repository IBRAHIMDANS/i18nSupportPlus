package com.ibrahimdans.i18n.extensions.technology.tscatalog

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.SOURCE_ROOT
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.lang.ref.SoftReference

/**
 * Discovers a translation catalog written as a plain TypeScript/JavaScript object whose
 * top-level keys are locale codes:
 *
 * ```ts
 * export const translations = {
 *   fr: { common: { cancel: 'Annuler' } },
 *   en: { common: { cancel: 'Cancel' } },
 * } as const;
 * ```
 *
 * That layout is the norm in React Native / Expo projects (i18n-js and friends) and used
 * to be invisible to the plugin: [com.ibrahimdans.i18n.extensions.localization.js.TsLocalization]
 * declares no file types, so the generic scan never opens a `.ts` file, and the only code
 * path that ever read a TS catalog —
 * [com.ibrahimdans.i18n.extensions.technology.i18next.I18NextTechnology.findSourcesByConfiguration] —
 * is hardwired to the i18next shape (a `resources` property holding `{ locale: { namespace } }`).
 * With no source found, every key in such a project was annotated as unresolved.
 *
 * This technology contributes sources only; the translation function names stay the business
 * of the framework technologies.
 */
class TsCatalogTechnology : Technology {

    companion object {
        private val SOURCES_CACHE = Key.create<SoftReference<CachedSources>>("i18n.tscatalog.sources")

        /**
         * Opening and parsing every `.ts` file of a project on each key resolution is not an
         * option, so candidates are pre-filtered on the conventional places a catalog lives.
         * A catalog stored somewhere else is reachable by setting "Translations root directory".
         */
        private val CATALOG_DIRECTORY_NAMES = setOf(
            "i18n", "intl", "lang", "langs", "locale", "locales", "translation", "translations"
        )

        private val CATALOG_FILE_STEMS = setOf(
            "i18n", "intl", "lang", "langs", "locale", "locales",
            "messages", "strings", "translation", "translations"
        )

        private val FILE_TYPE_NAMES = listOf("TypeScript", "TypeScript JSX", "JavaScript")

        /**
         * node_modules is the one exclusion that must hold even when the IDE has not marked
         * it excluded (a freshly opened project, a test fixture): a single dependency ships
         * enough locale-shaped objects to swamp the result.
         */
        private const val NODE_MODULES = "node_modules"
    }

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun cfgNamespaces(): List<String> = emptyList()

    /**
     * Warms the cache off the EDT once indices are ready, so the first highlighting pass does
     * not pay for the scan. Resolution never depends on this having run: [findSourcesByConfiguration]
     * computes on demand.
     */
    override fun initialize(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            ReadAction.nonBlocking<Unit> { findSourcesByConfiguration(project) }
                .expireWith(project)
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    /**
     * Called on every key resolution through `LocalizationSourceService.findSources`, which is
     * itself called once per highlighted element — hence the project-level cache, stamped and
     * invalidated exactly like the one guarding `findAllSources`.
     */
    override fun findSourcesByConfiguration(project: Project): List<LocalizationSource> {
        val config = Settings.getInstance(project).config()
        val stamps = CacheStamps(
            psi = PsiModificationTracker.getInstance(project).modificationCount,
            roots = ProjectRootManager.getInstance(project).modificationCount,
            config = config.hashCode()
        )
        cachedSources(project, stamps)?.let { return it }
        // FileTypeIndex is unavailable during indexing; serve nothing rather than throw, and
        // leave the cache untouched so the next smart-mode call computes for real.
        if (DumbService.isDumb(project)) return emptyList()

        val sources = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            computeSources(project, config)
        }
        project.putUserData(SOURCES_CACHE, SoftReference(CachedSources(stamps, sources)))
        return sources
    }

    private fun cachedSources(project: Project, stamps: CacheStamps): List<LocalizationSource>? {
        val cached = project.getUserData(SOURCES_CACHE)?.get() ?: return null
        if (cached.stamps != stamps) return null
        // A file reloaded from disk leaves invalid PSI behind; handing it out would throw
        // PsiInvalidElementAccessException in the callers.
        if (cached.sources.any { it.tree?.value()?.isValid == false }) return null
        return cached.sources
    }

    private fun computeSources(project: Project, config: Config): List<LocalizationSource> {
        val localization = tsLocalization() ?: return emptyList()
        val basePath = project.basePath ?: ""
        val scope = config.searchScope(project)
        return fileTypes()
            .flatMap { FileTypeIndex.getFiles(it, scope) }
            .distinct()
            .filter { isCandidate(it, project, config, basePath) }
            .mapNotNull { PsiManager.getInstance(project).findFile(it) }
            .flatMap { file -> sourcesOf(file, localization, basePath) }
    }

    private fun sourcesOf(
        file: PsiFile,
        localization: Localization<PsiElement>,
        basePath: String
    ): List<LocalizationSource> {
        val entries = findLocaleCatalog(file)
        if (entries.isEmpty()) return emptyList()
        // Lets TsReferenceAssistant offer translation -> code references from this file.
        file.putUserData(SOURCE_ROOT, true)
        val relativePath = pathToRoot(basePath, file.virtualFile?.path ?: return emptyList()).trim('/')
        return entries.map { (locale, catalog) ->
            LocalizationSource(
                localization.elementsTree(catalog),
                file.name,
                // `parent` carries the locale for every consumer that needs one: folding and
                // inlay hints compare it to the preferred language, the extract dialog lists
                // locales from it, and TranslationDataLoader.extractLocale reads it first.
                locale,
                // One file yields one source per locale, so the path alone would not be unique
                // and `distinctBy { displayPath }` would keep a single locale.
                "$relativePath#$locale",
                localization
            )
        }
    }

    /**
     * Returns the locale entries of the catalog held by [file], empty when it holds none.
     *
     * Internal rather than private: the detection rules are what this class is about, and
     * they are worth testing without going through the file index.
     */
    internal fun findLocaleCatalog(file: PsiFile): List<Pair<String, JSObjectLiteralExpression>> {
        if (isI18nextConfig(file)) return emptyList()
        return PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java)
            .asSequence()
            .filter { isTopLevelLiteral(it) }
            .mapNotNull { localeEntries(it) }
            .firstOrNull()
            ?: emptyList()
    }

    /**
     * i18next declares its catalog as `resources: { en: { translation: {…} } }`, whose value
     * also has locale keys. Leaving it to both technologies would list every namespace twice
     * — `findSources` de-duplicates on `displayPath`, which differs between the two — so the
     * i18next shape is left to its owner.
     */
    private fun isI18nextConfig(file: PsiFile): Boolean =
        PsiTreeUtil.findChildrenOfType(file, JSProperty::class.java)
            .any { it.name == "resources" && it.value is JSObjectLiteralExpression }

    /**
     * Keeps object literals that stand on their own at module level: not nested in another
     * literal (an inner `{ en: … }` is data, not the catalog) and not a call argument
     * (`i18n.init({…})`, `createIntl({…})` belong to their framework's technology).
     */
    private fun isTopLevelLiteral(literal: JSObjectLiteralExpression): Boolean =
        PsiTreeUtil.getParentOfType(literal, JSObjectLiteralExpression::class.java) == null &&
            PsiTreeUtil.getParentOfType(literal, JSArgumentList::class.java) == null

    /**
     * A catalog is an object whose properties are *all* a locale code mapping to a nested
     * object. Requiring every key to be a locale is what keeps ordinary objects out: two-letter
     * ISO codes collide with plenty of English words (`is`, `no`, `to`, `it`), but an object
     * where all keys happen to be language codes and all values are objects is a catalog.
     * Locale codes are validated by the shared ISO-backed rule, not by shape.
     */
    private fun localeEntries(literal: JSObjectLiteralExpression): List<Pair<String, JSObjectLiteralExpression>>? {
        val properties = literal.properties
        if (properties.isEmpty()) return null
        return properties.map { property ->
            val name = property.name ?: return null
            if (!LocalizationSourceService.looksLikeLocale(name)) return null
            val value = property.value as? JSObjectLiteralExpression ?: return null
            name to value
        }
    }

    /**
     * Internal rather than private: the "Translations root directory" branch cannot be
     * exercised end to end from a light fixture, whose files live in an in-memory VFS
     * (`/src/…`) disjoint from `project.basePath` (a real temp directory), so no configured
     * root can ever prefix them. The rule itself is testable by passing the base path in.
     */
    internal fun isCandidate(file: VirtualFile, project: Project, config: Config, basePath: String): Boolean {
        if (file.path.split('/').any { it == NODE_MODULES }) return false
        if (ProjectFileIndex.getInstance(project).isExcluded(file)) return false
        if (file.path.split('/').any { it in config.excludedDirectorySet() }) return false
        val root = config.translationsRoot
        if (root.isNotBlank()) return file.path.startsWith("$basePath/${root.trim('/')}")
        return file.parent?.name?.lowercase() in CATALOG_DIRECTORY_NAMES ||
            file.nameWithoutExtension.lowercase() in CATALOG_FILE_STEMS
    }

    /** The registered TsLocalization instance, so sources carry the extension the IDE knows. */
    private fun tsLocalization(): Localization<PsiElement>? =
        Extensions.LOCALIZATION.extensionList.firstOrNull { it.config().id() == "ts" }

    private fun fileTypes(): List<FileType> =
        FILE_TYPE_NAMES.mapNotNull { FileTypeManager.getInstance().findFileTypeByName(it) }

    /** Everything [findSourcesByConfiguration] depends on; any change invalidates the scan. */
    private data class CacheStamps(val psi: Long, val roots: Long, val config: Int)

    private class CachedSources(val stamps: CacheStamps, val sources: List<LocalizationSource>)
}
