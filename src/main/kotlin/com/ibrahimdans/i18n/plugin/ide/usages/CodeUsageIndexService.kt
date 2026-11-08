package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.tree.ContextKey
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.usages.CallSiteIndex
import com.ibrahimdans.i18n.plugin.usages.DynamicReach
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.keyScopesOf
import com.ibrahimdans.i18n.plugin.utils.searchScope
import com.intellij.json.JsonFileType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The key usages of the project's code, read once and kept: an inverted index from key path to the
 * places naming it ([CallSiteIndex]), filled file by file from the PSI ([CodeUsageIndexer]). It answers
 * what the word searches of `IndirectKeyUsages` and `DynamicKeyUsages` answer — the text scan, the
 * key-prefix and context scan, the dynamic keys, the translation values linking to a key — for every
 * key at once, where those searched the project once per key.
 *
 * Built at the first question, each file in its own non-blocking read action: a write action cancels
 * the file being read, which is read again, never the pass. Then kept up to date per file: an edited
 * file is read again ([PsiTreeChangeAdapter], [BulkFileListener] for a change from outside the IDE),
 * an added, moved or deleted one is found by listing the files again when the file structure or the
 * project roots changed. A change of [Settings] — separators, namespaces, function names — or of the
 * key scopes the technologies declare drops it all: the next question rebuilds it.
 *
 * What depends on the module asked, the `fallbackNS` and whether the code names keys as identifiers
 * is applied when asking, never when indexing.
 */
@Service(Service.Level.PROJECT)
internal class CodeUsageIndexService(private val project: Project) : Disposable {

    /** The sites of each code file indexed. Guarded by `this`, as are [hosts] and [linked]. */
    private val sites = CallSiteIndex<CodeSite>()

    /** The code files indexed, by path. */
    private val hosts = mutableMapOf<String, VirtualFile>()

    /** The translation values linking to a key, read again whole whenever a translation file changes. */
    private var linked: List<LinkedSite> = emptyList()

    /** The settings and key scopes the index was built with. */
    @Volatile
    private var builtWith: Pair<Config, Set<String>>? = null

    /** The file structure and project roots the files were last listed under. */
    @Volatile
    private var listedAt = -1L

    @Volatile
    private var linkedStale = true

    /** The files to read again, each with the generation it was marked at: a read started before a later mark is dropped. */
    private val dirty = ConcurrentHashMap<VirtualFile, Long>()
    private val generation = AtomicLong()

    /** How many code files were read so far: what the tests count to tell a refresh from a rebuild. */
    private val filesRead = AtomicLong()

    @TestOnly
    fun filesRead(): Long = filesRead.get()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
            override fun childMoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file
                    if (event !is VFileContentChangeEvent || file == null) linkedStale = true
                    else changed(file)
                }
            }
        })
    }

    /** Never takes the lock: the PSI events arrive in a write action, which a read holding it may wait for. */
    private fun onChange(event: PsiTreeChangeEvent) {
        // A change inside no file is structural: the next question lists the files again.
        val file = event.file ?: run { linkedStale = true; return }
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file) ?: file
        top.viewProvider.virtualFile.let(::changed)
    }

    private fun changed(file: VirtualFile) {
        if (isTranslationValueType(file.fileType)) linkedStale = true
        if (isCodeType(file.fileType)) dirty[file] = generation.incrementAndGet()
    }

    /** What the code of [module] — the whole project for none — uses of each key in [keys]. */
    fun usages(keys: Collection<String>, module: ModuleConfig?): Map<String, Found> {
        val config = refresh()
        val asked = readStep { Asked(project, config, module) }
        return synchronized(this) { keys.distinct().associateWith { key -> asked.found(key) } }
    }

    /** The subset of [keys] a key the code of [module] builds at runtime may produce. */
    fun dynamicallyReached(keys: Collection<String>, module: ModuleConfig?): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val config = refresh()
        val asked = readStep { Asked(project, config, module) }
        // The words the dynamic search looked for, on behalf of the whole batch: a literal it found for one
        // key may reach another.
        val words = DynamicKeyUsages.searchWords(keys.toList(), config.nsSeparator, config.keySeparator)
        val shapes = synchronized(this) {
            sites.sites().filterIsInstance<DynamicSite>()
                .filter { site -> asked.covers(site.host) && words.any { containsWord(site.text, it) } }
                .mapTo(mutableSetOf()) { it.shape }
        }
        return DynamicReach.reached(keys, shapes, config.nsSeparator, config.keySeparator)
    }

    /** The places using one key, as the searches it replaces sorted them: the calls, the linking values, the prefixed calls. */
    class Found(val calls: Set<KeyUsage>, val linked: Set<KeyUsage>, val prefixed: Set<KeyUsage>) {
        /** As the old searches counted: the three sets apart. */
        val count: Int get() = calls.size + linked.size + prefixed.size
        val all: List<KeyUsage> get() = (calls + linked + prefixed).toList()
    }

    /** What a question is answered with, read in one read action. */
    private inner class Asked(project: Project, val config: Config, module: ModuleConfig?) {
        private val scope: GlobalSearchScope = ModuleSources.searchScope(project, module)
        private val covered: Set<String> = synchronized(this@CodeUsageIndexService) {
            hosts.filterValues(scope::contains).keys + linked.map { it.file }.distinct().filter(scope::contains).map { it.path }
        }
        private val keyScopes = keyScopesOf(project)
        private val fallbacks = FallbackNamespaces.of(IdeWorkspace(project), config, IdeI18nRegistry)
        private val identifiers = IndirectKeyUsages.keySearchContext(project).toInt() and UsageSearchContext.IN_CODE.toInt() != 0
        private val defaultNamespaces = config.defaultNamespaces()

        fun covers(host: String) = host in covered

        fun found(key: String): Found {
            val query = IndirectKeyUsages.usageQuery(key, config.pluralSeparator)
            val namespaces = query.namespace?.let { listOf(it) } ?: defaultNamespaces
            val calls = textSites(query.bareKey)
                .filter { covers(it.host) && (identifiers || !it.identifier) && worksIn(it, namespaces) }
                .mapTo(sortedSetOf(USAGE_ORDER)) { it.usage }
            val linking = linked
                .filter { it.key == key && covers(it.file.path) && query.words.any { word -> containsWord(it.text, word) } }
                .mapTo(sortedSetOf(USAGE_ORDER)) { it.usage }
            return Found(calls, linking, prefixed(key))
        }

        /** The literals naming [bareKey] or a key under it: the prefix match of `ReferencesAccumulator`, on segments. */
        private fun textSites(bareKey: String): Sequence<TextSite> {
            if (bareKey.isEmpty()) return emptySequence()
            if (config.keySeparator.isEmpty()) {
                return sites.sites().filterIsInstance<TextSite>().filter { site ->
                    site.written.startsWith(bareKey) && site.written.getOrNull(bareKey.length)?.isJavaIdentifierPart() != true
                }
            }
            return sites.under(CodeUsageIndexer.pathOf(bareKey, config)).asSequence().filterIsInstance<TextSite>()
        }

        /**
         * `ReferencesAccumulator.underExpectedNamespace`: the namespace a call writes wins; with none
         * written, the hook's; a call declaring none works in every namespace.
         */
        private fun worksIn(site: TextSite, namespaces: List<String>): Boolean {
            if (namespaces.isEmpty()) return true
            if (site.namespace != null) return site.namespace in namespaces
            val declared = site.declared
            return declared == null || declared.any { it in namespaces } || fallbacks.any { it in namespaces }
        }

        /** `IndirectKeyUsages.prefixedUsages` for one key. */
        private fun prefixed(key: String): Set<KeyUsage> {
            val found = sortedSetOf(USAGE_ORDER)
            val namespace = KeySpelling.namespaceOf(key)
            for ((segments, context) in wantedPaths(key)) {
                if (segments.lastOrNull().isNullOrBlank()) continue
                val candidates = sites.at(segments) + sites.at(segments.drop(1))
                for (site in candidates) {
                    if (site !is PrefixedSite || !covers(site.host)) continue
                    // A scope key is spelled `admin.title`: the scope is its first segment, not a namespace.
                    val scope = site.scope?.takeIf { it in keyScopes }
                    if (listOfNotNull(scope) + site.path != segments) continue
                    val prefixed = site.keyPrefixed || scope != null
                    if (!prefixed && site.context == null) continue
                    if (if (context == null) !prefixed else !ContextKey.reads(site.context, context)) continue
                    val namespaces = if (scope != null) emptyList() else site.namespaces
                    val inNamespace = namespaces.isEmpty() ||
                        if (namespace == null) namespaces.any { it in defaultNamespaces } else namespace in namespaces
                    if (inNamespace) found += site.usage
                }
            }
            return found
        }

        /**
         * The paths a call naming [key] may write, as `IndirectKeyUsages.wantedPaths` reads them: the
         * key's own, and the base of a context variant (`friend_male`) under that context.
         */
        private fun wantedPaths(key: String): List<Pair<List<String>, String?>> {
            val segments = KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config)
            val own = segments to null
            if (config.keySeparator == ContextKey.SEPARATOR) return listOf(own)
            val (base, context) = segments.lastOrNull()?.let(ContextKey::split) ?: return listOf(own)
            return listOf(own, segments.dropLast(1) + base to context)
        }
    }

    /**
     * Brings the index up to date with the settings, the files present and the files changed, and
     * returns the settings it now holds. Reads off the lock, writes under it.
     */
    private fun refresh(): Config {
        val (config, keyScopes) = readStep { Settings.getInstance(project).config() to keyScopesOf(project) }
        if (config to keyScopes != builtWith) {
            synchronized(this) {
                sites.clear()
                hosts.clear()
                linked = emptyList()
            }
            dirty.clear()
            builtWith = config to keyScopes
            listedAt = -1
            linkedStale = true
        }
        val stamp = VirtualFileManager.getInstance().structureModificationCount + ProjectRootManager.getInstance(project).modificationCount
        if (stamp != listedAt) {
            val listed = readStep { codeFiles(config) }
            val known = synchronized(this) {
                (hosts.keys - listed.mapTo(mutableSetOf()) { it.path }).forEach { path -> sites.remove(path); hosts -= path }
                hosts.keys.toSet()
            }
            listed.filter { it.path !in known }.forEach { dirty.putIfAbsent(it, generation.incrementAndGet()) }
            listedAt = stamp
        }
        indexDirty(config, keyScopes)
        if (linkedStale) {
            linkedStale = false
            val read = readStep {
                val indexer = CodeUsageIndexer(project, config, keyScopes)
                translationValueFiles(config).mapNotNull { PsiManager.getInstance(project).findFile(it) }.flatMap(indexer::linkedSitesOf)
            }
            synchronized(this) { linked = read }
        }
        return config
    }

    private fun indexDirty(config: Config, keyScopes: Set<String>) {
        val pending = dirty.entries.map { it.key to it.value }
        if (pending.isEmpty()) return
        val indicator = ProgressManager.getInstance().progressIndicator
        indicator?.isIndeterminate = false
        val indexer = CodeUsageIndexer(project, config, keyScopes)
        pending.forEachIndexed { done, (file, marked) ->
            ProgressManager.checkCanceled()
            indicator?.fraction = done.toDouble() / pending.size
            val read = readStep {
                if (!file.isValid || !config.searchScope(project).contains(file)) null
                else PsiManager.getInstance(project).findFile(file)?.let(indexer::sitesOf)
            }
            if (read != null) filesRead.incrementAndGet()
            synchronized(this) {
                // Marked again while it was read: the next question reads it again.
                if (!dirty.remove(file, marked)) return@synchronized
                if (read == null) {
                    sites.remove(file.path)
                    hosts -= file.path
                } else {
                    sites.update(file.path, read)
                    hosts[file.path] = file
                }
            }
        }
    }

    private fun codeFiles(config: Config): List<VirtualFile> {
        val scope = config.searchScope(project)
        return FileTypeManager.getInstance().registeredFileTypes.filter(::isCodeType).flatMap { FileTypeIndex.getFiles(it, scope) }.distinct()
    }

    /** The JSON and YAML files: where a value linking to a key lives. */
    private fun translationValueFiles(config: Config): List<VirtualFile> {
        val scope = config.searchScope(project)
        return translationValueTypes().flatMap { FileTypeIndex.getFiles(it, scope) }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): CodeUsageIndexService = project.service()

        /**
         * The languages the [com.ibrahimdans.i18n.Lang]s read a key from, a dialect counting for its
         * base: JavaScript and TypeScript, PHP, Twig, and the templates embedding them (HTML, so Vue,
         * Angular and Svelte; Blade, Astro).
         */
        private val CODE_LANGUAGES = setOf("JavaScript", "PHP", "HTML", "Twig", "Blade", "Vue", "Astro")

        fun isCodeType(type: FileType): Boolean {
            val language = (type as? LanguageFileType)?.language ?: return false
            return generateSequence(language) { it.baseLanguage }.any { it.id in CODE_LANGUAGES }
        }

        private fun translationValueTypes(): List<FileType> =
            listOfNotNull(JsonFileType.INSTANCE, FileTypeManager.getInstance().findFileTypeByName("YAML"))

        private fun isTranslationValueType(type: FileType): Boolean = type in translationValueTypes()

        private val USAGE_ORDER = compareBy<KeyUsage>({ it.path }, { it.span.start }, { it.span.end })

        /**
         * Whether [text] holds [word] as the word index finds it: not glued to an identifier character
         * on a side where the word itself starts or ends with one.
         */
        internal fun containsWord(text: String, word: String): Boolean {
            if (word.isEmpty()) return false
            var at = text.indexOf(word)
            while (at >= 0) {
                val end = at + word.length
                val before = at == 0 || !word.first().isJavaIdentifierPart() || !text[at - 1].isJavaIdentifierPart()
                val after = end == text.length || !word.last().isJavaIdentifierPart() || !text[end].isJavaIdentifierPart()
                if (before && after) return true
                at = text.indexOf(word, at + 1)
            }
            return false
        }

        /**
         * Runs [compute] in a read action a write action cancels and restarts, rather than waits
         * for; inside a read action already, it simply runs.
         */
        fun <T> readStep(compute: () -> T): T = ReadAction.nonBlocking<T>(Callable { compute() }).executeSynchronously()
    }
}
