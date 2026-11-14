package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.*
import com.ibrahimdans.i18n.plugin.ide.settings.CodeDefaults
import com.ibrahimdans.i18n.plugin.ide.settings.CodeSettingsReader
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.I18nextCliConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.GlobalSearchScope
import com.ibrahimdans.i18n.plugin.utils.project
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/**
 * [I18nextCodeReader] of the JavaScript plugin: the i18next initialisation files found by the word
 * index and read with the JavaScript PSI, their inline `resources`, `fallbackNS`, the settings they
 * fill ([CodeSettingsReader]) and the configuration the setup wizard reads. Registered in
 * `jsConfig.xml`; [I18NextTechnology], in the trunk, asks for it and does without.
 */
class I18nextJsReader : I18nextCodeReader, CodeSettingsReader {

    private val MAX_RESOLVE_DEPTH = 10

    private var cfgNamespaces: List<String> = listOf()

    override fun initSources(project: Project): List<LocalizationSource> {
        val config = Settings.getInstance(project).config()
        val localizations = Extensions.LOCALIZATION.extensionList
        // Asked from a pooled thread too (the tool window): the configuration files are PSI to read.
        return ReadAction.compute<List<LocalizationSource>, RuntimeException> { configFiles(project, config)
            .flatMap {
                val init = PsiManager.getInstance(project).findFile(it)?.let { findInitObject(it) }
                val translations = getTranslations(
                    init?.let {listNamespaces(it)} ?: listOf()
                )
                translations.mapNotNull {
                    (name, expr) -> resolveReferenceExpression(expr, MAX_RESOLVE_DEPTH)?.let {Pair(name,it)}
                }.flatMap {
                    (name, expr) ->
                        localizations.filter {
                            it.matches(LocalizationFileType(expr.containingFile.fileType), null, listOf())
                        }.mapNotNull {
                            // `translation: en` with `import en from './en.json'` holds no object: an empty
                            // source, stating no namespace, kept a key without one from falling back to
                            // the JSON files themselves, and every such key was unresolved (#863).
                            val tree = it.elementsTree(expr) ?: return@mapNotNull null
                            expr.containingFile.putUserData(SOURCE_ROOT, true)
                            // One file holds every locale: the locale is the source's parent, and it
                            // keeps the path unique, or `distinctBy { displayPath }` kept the first locale.
                            LocalizationSource(tree, name, name, "${expr.containingFile.name}#$name", it, locale = name)
                        }
                }
            } }
    }

    override fun initialize(project: Project) {
        // Defer index access until indices are ready (fixes AssertionError on IntelliJ 2023.1+
        // where MappedFileTypeIndex.setAssociation() throws when called outside a proper read context).
        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            ReadAction.nonBlocking<List<String>> {
                val config = Settings.getInstance(project).config()
                val searchScope = config.searchScope(project)
                // A synchronized list: the search calls the processor from several threads at once.
                val cfgFileNames: MutableList<String> = Collections.synchronizedList(mutableListOf())
                @Suppress("DEPRECATION")
                if (PsiSearchHelper.SearchCostResult.FEW_OCCURRENCES == PsiSearchHelper.getInstance(project).isCheapEnoughToSearch("translation", searchScope, null)) {
                    val processor = object : TextOccurenceProcessor {
                        override fun execute(element: PsiElement, offsetInElement: Int): Boolean {
                            (element.parent as? JSProperty)?.whenMatches { it.name == "translation" }?.containingFile?.also {
                                it.putUserData(SOURCE_ROOT, true)
                                cfgFileNames.add(it.name)
                            }
                            return false
                        }
                    }
                    PsiSearchHelper.getInstance(project).processElementsWithWord(
                        processor, searchScope, "translation", UsageSearchContext.IN_CODE, true
                    )
                }
                cfgFileNames.toList()
            }
            .expireWith(project)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { result ->
                this.cfgNamespaces = result
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        }
    }

    override fun cfgNamespaces(): List<String> = cfgNamespaces

    /**
     * `fallbackNS` of the i18next configuration files the plugin reads ([configFiles]): a string or
     * an array of strings, in order. Asked for every key parsed, so the answer is kept until a
     * JavaScript or TypeScript file changes.
     */
    override fun fallbackNamespaces(project: Project): List<String> {
        val cache = fallbackCaches.computeIfAbsent(project) { project ->
            CachedValuesManager.getManager(project).createCachedValue {
                val tracker = PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(JavascriptLanguage) }
                CachedValueProvider.Result.create(readFallbackNamespaces(project), tracker)
            }
        }
        return ReadAction.compute<List<String>, RuntimeException> { cache.value }
    }

    /** One cache per project, held by this instance: the configuration files it reads are its own. */
    private val fallbackCaches = ContainerUtil.createConcurrentWeakMap<Project, CachedValue<List<String>>>()

    private fun readFallbackNamespaces(project: Project): List<String> {
        val psiManager = PsiManager.getInstance(project)
        return configFiles(project, Settings.getInstance(project).config())
            .mapNotNull { psiManager.findFile(it) }
            .flatMap { file -> PsiTreeUtil.findChildrenOfType(file, JSProperty::class.java).filter { it.name == "fallbackNS" } }
            .flatMap { property -> fallbackValues(property.value) }
            .distinct()
    }

    /** The namespaces `fallbackNS: 'common'` or `fallbackNS: ['common', 'shared']` names. */
    internal fun fallbackValues(value: JSExpression?): List<String> = when (value) {
        is JSLiteralExpression -> listOfNotNull(value.stringValue?.takeIf { it.isNotBlank() })
        is JSArrayLiteralExpression -> value.expressions.flatMap { fallbackValues(it) }
        else -> emptyList()
    }

    /**
     * The i18next configuration files to read `resources` and `fallbackNS` from.
     *
     * [Config.jsConfiguration], when set, names them: comma-separated paths relative to the project
     * (a content root or the project directory), JS or TS alike. It had been stored since the first
     * release without anything reading it. Left empty, the files are the initialisation files and
     * `i18next.config.*` the project holds ([discoveredFiles]), then the ones [initialize] found by
     * searching the word `translation`, looked up by name among TypeScript files.
     */
    internal fun configFiles(project: Project, config: Config): List<VirtualFile> {
        val configured = config.jsConfiguration.split(',').map { it.trim().trim('/') }.filter { it.isNotEmpty() }
        if (configured.isNotEmpty()) {
            return configured.mapNotNull { path -> roots(project).firstNotNullOfOrNull { it.findFileByRelativePath(path) } }
                .filter { !it.isDirectory }
                .distinct()
        }
        val tsFileType = FileTypeManager.getInstance().findFileTypeByName("TypeScript") ?: return discoveredFiles(project)
        val searchScope = config.searchScope(project)
        val found = cfgNamespaces.mapNotNull { cfgFile -> FileTypeIndex.getFiles(tsFileType, searchScope).find { it.name == cfgFile } }
        return (discoveredFiles(project) + found).distinct()
    }

    private fun roots(project: Project): List<VirtualFile> =
        ProjectRootManager.getInstance(project).contentRoots.toList() +
            listOfNotNull(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) })

    /**
     * The files that initialise i18next ([I18nextInitReader]), then the `i18next.config.*` of a
     * content root, in the project's own files: found by what they hold, whatever their name.
     *
     * The word index narrows the search to the files holding both a call name of
     * [I18nextInitReader.CALL_NAMES] and the word `i18next` — one or two files, where
     * reading every file importing `react-i18next` would parse hundreds. Kept until a JavaScript or
     * TypeScript file changes, and empty while the indexes are built.
     */
    internal fun discoveredFiles(project: Project): List<VirtualFile> {
        // Asked from any thread — the tool window loads from a pooled one, holding no read action —
        // so the search opens its own, and gives up while the indexes are built.
        if (project.isDisposed || DumbService.isDumb(project)) return emptyList()
        val cache = discoveryCaches.computeIfAbsent(project) {
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(
                    discover(project),
                    PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(JavascriptLanguage) },
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DumbService.getInstance(project).modificationTracker,
                )
            }
        }
        return try {
            ReadAction.compute<List<VirtualFile>, RuntimeException> { cache.value }
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private val discoveryCaches = ContainerUtil.createConcurrentWeakMap<Project, CachedValue<List<VirtualFile>>>()

    private fun discover(project: Project): List<VirtualFile> {
        if (DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val cache = CacheManager.getInstance(project)
        val withModule = cache.getVirtualFilesWithWord(I18nextInitReader.MODULE, UsageSearchContext.ANY, scope, true).toSet()
        val psiManager = PsiManager.getInstance(project)
        val initFiles = I18nextInitReader.CALL_NAMES
            .flatMap { cache.getVirtualFilesWithWord(it, UsageSearchContext.ANY, scope, true).toList() }
            .filter { it in withModule && NODE_MODULES !in it.path }
            .distinct()
            .sortedBy { it.path }
            .filter { file -> psiManager.findFile(file)?.let { I18nextInitReader.read(it) } != null }
        val cliConfigs = roots(project).distinct().mapNotNull { root ->
            I18nextCliConfig.FILE_NAMES.firstNotNullOfOrNull { root.findChild(it) }
        }
        return (initFiles + cliConfigs).distinct()
    }

    /**
     * What the project's i18next initialisation says of the settings [CodeDefaults] fills: `defaultNS`
     * and `fallbackLng` of the first [configFiles] file stating them, else `extract.defaultNS` of an
     * `i18next.config.*`. Null when no file says anything, or while the indexes are built.
     *
     * Asked by every read of the settings with a blank to fill, so the answer is kept until a
     * JavaScript or TypeScript file changes — per [Config.jsConfiguration] and search scope, the
     * only settings it depends on.
     */
    override fun codeDefaults(project: Project, config: Config): CodeDefaults? {
        if (project.isDisposed || DumbService.isDumb(project)) return null
        val answers = codeDefaultsCaches.computeIfAbsent(project) {
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap<Pair<String, Boolean>, CodeDefaultsAnswer>(),
                    PsiModificationTracker.getInstance(project).forLanguages { it.isKindOf(JavascriptLanguage) },
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DumbService.getInstance(project).modificationTracker,
                )
            }
        }.value
        val key = config.jsConfiguration to config.searchInProjectOnly
        answers[key]?.let { return it.defaults }
        return try {
            ReadAction.compute<CodeDefaults?, RuntimeException> { readCodeDefaults(project, config) }
                .also { answers[key] = CodeDefaultsAnswer(it) }
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    /** A [codeDefaults] answer, null included: a concurrent map holds no null value. */
    private class CodeDefaultsAnswer(val defaults: CodeDefaults?)

    private val codeDefaultsCaches =
        ContainerUtil.createConcurrentWeakMap<Project, CachedValue<ConcurrentHashMap<Pair<String, Boolean>, CodeDefaultsAnswer>>>()

    private fun readCodeDefaults(project: Project, config: Config): CodeDefaults? {
        val psiManager = PsiManager.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)
        val read = configFiles(project, config).mapNotNull { file ->
            val psiFile = psiManager.findFile(file) ?: return@mapNotNull null
            val path = fileIndex.getContentRootForFile(file)?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
            val options = I18nextInitReader.read(psiFile)
            if (options != null) CodeDefaults(path, options.defaultNs, options.fallbackLng)
            else I18nextCliConfigReader.read(psiFile, path)?.let { cli -> CodeDefaults(path, listOfNotNull(cli.defaultNs)) }
        }
        val defaults = read.firstOrNull { it.defaultNs.isNotEmpty() }
        val locale = read.firstNotNullOfOrNull { it.referenceLocale }
        val origin = defaults?.origin ?: read.firstOrNull { it.referenceLocale != null }?.origin ?: return null
        return CodeDefaults(origin, defaults?.defaultNs.orEmpty(), locale)
    }

    /**
     * Finds the i18next `resources` object in a config file, or null when the config
     * does not declare one inline — which is the norm when translations are loaded at
     * runtime (i18next-http-backend and friends).
     *
     * The `resources` property used to be looked up with `.first()`, so such a config
     * threw NoSuchElementException. Since this runs inside `findSourcesByConfiguration`,
     * which every key resolution goes through (annotator, completion, folding, hints,
     * gutter icons, tool window), it broke the plugin wholesale on those projects.
     */
    internal fun findInitObject(root: PsiElement): JSObjectLiteralExpression? {
        val resources = PsiTreeUtil.findChildrenOfType(root, JSProperty::class.java).firstOrNull {
            it.name == "resources" && it.value is JSObjectLiteralExpression
        } ?: return null
        return PsiTreeUtil.findFirstParent(resources) { it is JSObjectLiteralExpression } as? JSObjectLiteralExpression
    }

    private fun listNamespaces(init: JSObjectLiteralExpression): List<JSProperty> {
        return init.findProperty("resources")?.value?.let {it as? JSObjectLiteralExpression}?.properties?.toList() ?: listOf()
    }

    private fun getTranslations(namespaces: List<JSProperty>): List<Pair<String, JSExpression>> {
        return namespaces.filter { it.value is JSObjectLiteralExpression }.mapNotNull (::getTranslation)
    }

    private fun getTranslation(element: JSProperty): Pair<String, JSExpression>? {
        return element.name?.let {
            name -> (element.value as? JSObjectLiteralExpression)?.findProperty("translation")?.value?.let {Pair(name, it)}
        }
    }

    private tailrec fun resolveReferenceExpression(expr: PsiElement?, maxDepth: Int): PsiElement? {
        if(maxDepth == 0) return null
        return if(!(expr is PsiReference)) expr else resolveReferenceExpression(expr.resolve(), maxDepth-1)
    }

    override fun wizardCliConfig(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): I18nextCliConfig? =
        I18nextCliConfigReader.readForWizard(file, relativePath, isDirectory)

    /** The initialisation files [discoveredFiles] found, the `i18next.config.*` it adds left out. */
    override fun wizardInitFiles(project: Project): List<VirtualFile> =
        discoveredFiles(project).filter { it.name !in I18nextCliConfig.FILE_NAMES }

    companion object {
        private const val NODE_MODULES = "/node_modules/"
    }
}
