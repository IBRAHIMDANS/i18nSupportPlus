package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.Extensions
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
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.keyScopesOf
import com.ibrahimdans.i18n.plugin.utils.searchScope
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.json.JsonFileType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * The key usages of the project's code, read once and kept: an inverted index from key path to the
 * places naming it ([CallSiteIndex]), filled file by file from the PSI ([CodeUsageIndexer]). It answers
 * what the word searches of `IndirectKeyUsages` and `DynamicKeyUsages` answered — the text scan, the
 * key-prefix and context scan, the dynamic keys, the translation values linking to a key — for every
 * key at once, where those searched the project once per key.
 *
 * Built at the first question, each file in its own non-blocking read action: a write action cancels
 * the file being read, which is read again, never the pass. Then kept up to date per file: an edited
 * file is read again ([PsiTreeChangeAdapter], [BulkFileListener] for a change from outside the IDE),
 * an added, moved or deleted one is found by listing the files again when the file structure or the
 * project roots changed. A change of [Settings] — separators, namespaces, function names — or of the
 * key scopes the technologies declare drops it all: the next question rebuilds it, and a rebuild
 * still running for the settings before is dropped with them.
 *
 * What depends on the module asked, the `fallbackNS` and whether the code names keys as identifiers
 * is applied when asking, never when indexing.
 */
@Service(Service.Level.PROJECT)
internal class CodeUsageIndexService(private val project: Project) : Disposable {

    /** Held by the one [refresh] running. */
    private val refreshing = ReentrantLock()

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

    /** How many places the index holds: the code's call sites, then those in translation values. */
    @TestOnly
    fun sitesHeld(): Pair<Int, Int> = synchronized(this) { sites.sites().count() to linked.size }

    /** Drops what was read: the next question reads the project again, as after opening it. */
    @TestOnly
    fun forget() {
        builtWith = null
    }

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

    /**
     * The call sites of [module]'s code naming [bareKey] — a key written without its namespace — read
     * from one of [namespaces]: a Symfony call reads its domain from an argument, never from the key.
     */
    fun calls(bareKey: String, namespaces: List<String>, module: ModuleConfig?): Set<KeyUsage> {
        val config = refresh()
        val asked = readStep { Asked(project, config, module) }
        return synchronized(this) { asked.calls(bareKey, namespaces) }
    }

    /** Whether a question has built the index already: asking it now reads the files changed since, not the project. */
    val isBuilt: Boolean get() = builtWith != null && listedAt >= 0

    /**
     * Whether the index answers a highlighting pass now: built with the settings and key scopes in force,
     * so that a question reads at most the files changed since. When it does not, it is built in the
     * background — one build at a time, under a progress indicator — and the highlighting runs again
     * once it is: reading the whole project from a highlighting pass held it for as long (18 s on the
     * benchmark's 300 files), and an answer read from a half-built index would call live keys unused.
     * A build running for settings that changed since is cancelled: the pass asking for the new ones
     * would wait for it to end, then start over.
     */
    fun readyForHighlighting(): Boolean {
        val wanted = wantedState()
        if (isReady(wanted)) return true
        if (holdBuilds) return false
        // A test reads what the counts decide: built here, in the pass that asks.
        if (ApplicationManager.getApplication().isUnitTestMode) {
            tryBuild()
            return isReady(wantedState())
        }
        synchronized(buildLock) {
            val running = build
            if (running == null) build = Build(wanted).also { it.job = buildIn(it) }
            else if (running.state != wanted) running.job.cancel()
        }
        return false
    }

    /** A build running, with the settings and key scopes it reads with. */
    private class Build(val state: Pair<Config, Set<String>>) {
        lateinit var job: Job
    }

    private val buildLock = Any()

    /** The build running, null between two. Guarded by [buildLock]. */
    private var build: Build? = null

    /**
     * Builds the index in the background, then reruns the highlighting that asked for it. Only after a
     * build: a failed one rerunning the highlighting would ask for the same build again, and fail again.
     * A cancelled one reruns it too: the pass then asks for the settings that cancelled it.
     */
    private fun buildIn(build: Build): Job = PluginCoroutineScope.of(project).launch {
        var rerun = false
        try {
            withBackgroundProgress(project, PluginBundle.message("usages.index.progress.title"), cancellable = false) {
                coroutineToIndicator { rerun = tryBuild() }
            }
        } catch (e: CancellationException) {
            rerun = true
            throw e
        } catch (e: ProcessCanceledException) {
            rerun = true
            throw e
        } finally {
            synchronized(buildLock) { if (this@CodeUsageIndexService.build === build) this@CodeUsageIndexService.build = null }
            if (rerun) ApplicationManager.getApplication().invokeLater({ rerunHighlighting() }, project.disposed)
        }
    }

    /**
     * Runs the highlighting again once the index is built, Code Vision included. A Code Vision pass
     * runs again only after the PSI changed since the editor's last one, and a build changes none:
     * the translation file open while it ran kept the empty lenses of that pass until reopened.
     */
    @RequiresEdt
    internal fun rerunHighlighting() {
        val documents = FileDocumentManager.getInstance()
        EditorFactory.getInstance().allEditors
            .filter { it.project == project && documents.getFile(it.document)?.fileType == JsonFileType.INSTANCE }
            .forEach { ModificationStampUtil.clearModificationStamp(it) }
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    /**
     * Builds the index, unless the last build failed on the same settings, key scopes and code: it would
     * fail again, and each highlighting pass would ask for it. A change of any of them tries once more.
     */
    private fun tryBuild(): Boolean {
        val state = buildState()
        if (state == failedOn) return false
        builds.incrementAndGet()
        return try {
            if (failBuilds) throw IllegalStateException("build failed for the test")
            refresh()
            failedOn = null
            true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Reading the code's key usages failed; not tried again before the settings or the code change", e)
            failedOn = state
            false
        }
    }

    /**
     * What a build reads: the settings and key scopes, the files present (the stamp [refresh] lists them
     * under) and the code files edited since ([generation] moves with each).
     */
    private fun buildState(): Any = readStep {
        listOf(Settings.getInstance(project).config(), keyScopesOf(project), filesStamp(), generation.get())
    }

    /**
     * The state of the code as the index follows it ([buildState]): it changes with the settings, the
     * files present and each code file edited. A count read from the code beside the index — the
     * platform's reference search — stays true while it is the same.
     */
    fun codeState(): Any = buildState()

    private fun filesStamp(): Long =
        VirtualFileManager.getInstance().structureModificationCount + ProjectRootManager.getInstance(project).modificationCount

    /** The [buildState] the last build failed on, null after a build that did not fail. */
    @Volatile
    private var failedOn: Any? = null

    /** How many builds were tried: a failing one is tried once, not once per highlighting pass. */
    private val builds = AtomicLong()

    @TestOnly
    fun buildsTried(): Long = builds.get()

    /** Set by a test to make every build fail, as a file the indexer cannot read would. */
    @Volatile
    @TestOnly
    var failBuilds = false

    /** The settings and key scopes in force: what the index must be built with to answer. */
    private fun wantedState(): Pair<Config, Set<String>> = readStep { Settings.getInstance(project).config() to keyScopesOf(project) }

    private fun isReady(wanted: Pair<Config, Set<String>>): Boolean = listedAt >= 0 && builtWith == wanted

    /** Set by a test to see what the highlighting does before the index is built. */
    @Volatile
    @TestOnly
    var holdBuilds = false

    /**
     * The elements behind [usages], in the caller's read action: the literal at each place, found
     * again in the file indexed there. A place whose file changed since it was indexed may find
     * nothing, or another element: the caller asks again after the next question refreshed it.
     */
    fun elementsOf(usages: Collection<KeyUsage>): List<PsiElement> {
        val files = synchronized(this) { hosts + linked.associate { it.file.path to it.file } }
        val psi = PsiManager.getInstance(project)
        val injection = InjectedLanguageManager.getInstance(project)
        return usages.mapNotNull { usage ->
            val file = files[usage.path]?.takeIf { it.isValid }?.let(psi::findFile) ?: return@mapNotNull null
            elementAt(file, TextRange(usage.span.start, usage.span.end), injection)
        }
    }

    /**
     * The element spanning exactly [range] of [file] or of a fragment injected into it: the innermost
     * one that is its own literal, as the searches returned it, else the outermost of that span.
     */
    private fun elementAt(file: PsiFile, range: TextRange, injection: InjectedLanguageManager): PsiElement? {
        val languages = Extensions.LANG.extensionList
        val starts = listOfNotNull(injection.findInjectedElementAt(file, range.startOffset)) +
            file.viewProvider.allFiles.mapNotNull { it.findElementAt(range.startOffset) }
        for (start in starts) {
            val spanning = start.parents(true).takeWhile { it !is PsiFile }
                .filter { (it.textRange?.let { own -> injection.injectedToHost(it, own) }) == range }
                .toList()
            if (spanning.isEmpty()) continue
            return spanning.firstOrNull { element -> languages.any { it.resolveLiteral(element) === element } } ?: spanning.last()
        }
        return null
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
            val calls = calls(query.bareKey, query.namespace?.let { listOf(it) } ?: defaultNamespaces)
            val linking = linked
                .filter { it.key == key && covers(it.file.path) && query.words.any { word -> containsWord(it.text, word) } }
                .mapTo(sortedSetOf(USAGE_ORDER)) { it.usage }
            return Found(calls, linking, prefixed(key))
        }

        /** The call sites naming [bareKey] or a key under it, in one of [namespaces]. */
        fun calls(bareKey: String, namespaces: List<String>): Set<KeyUsage> = textSites(bareKey)
            .filter { covers(it.host) && (identifiers || !it.identifier) && worksIn(it, namespaces) }
            .mapTo(sortedSetOf(USAGE_ORDER)) { it.usage }

        /** The literals naming [bareKey] or a key under it: the prefix match of `ReferencesAccumulator`, on segments. */
        private fun textSites(bareKey: String): Sequence<TextSite> {
            if (bareKey.isEmpty()) return emptySequence()
            if (config.keySeparator.isEmpty()) {
                // The sites writing [bareKey] or a longer key starting with it follow one another in [byWritten].
                var low = 0
                var high = byWritten.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (byWritten[middle].written < bareKey) low = middle + 1 else high = middle
                }
                return byWritten.subList(low, byWritten.size).asSequence()
                    .takeWhile { it.written.startsWith(bareKey) }
                    .filter { it.written.getOrNull(bareKey.length)?.isJavaIdentifierPart() != true }
            }
            return sites.under(CodeUsageIndexer.pathOf(bareKey, config)).asSequence().filterIsInstance<TextSite>()
        }

        /**
         * With no key separator, the text sites sorted by the key they write: a key is then found by a prefix search
         * rather than a scan of every site (28× the time with `.` on the usage benchmark). Read under the service's
         * lock, as the questions asking it are, once per question.
         */
        private val byWritten: List<TextSite> by lazy(LazyThreadSafetyMode.NONE) {
            sites.sites().filterIsInstance<TextSite>().sortedBy { it.written }.toList()
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

        /**
         * The calls reading [key] through what they do not write: a hook's key prefix, a key scope
         * (Transloco's `read`, spelled `scope.key`), an i18next context. The text scan counts the others.
         */
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
         * The paths a call naming [key] may write: the
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
     *
     * One at a time: two at once each saw the settings changed and cleared what the other was reading.
     * The wait gives way to a cancellation, so that a highlighting pass waiting here never holds back
     * the write action that cancelled it.
     */
    private fun refresh(): Config {
        while (!refreshing.tryLock(WAIT_MS, TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled()
        try {
            return refreshAlone()
        } finally {
            refreshing.unlock()
        }
    }

    private fun refreshAlone(): Config {
        val (config, keyScopes) = wantedState()
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
        val stamp = filesStamp()
        if (stamp != listedAt) {
            val listed = readStep { codeFiles(config) }
            val known = synchronized(this) {
                (hosts.keys - listed.mapTo(mutableSetOf()) { it.path }).forEach { path -> sites.remove(path); hosts -= path }
                hosts.toMap()
            }
            // A file deleted and created again at the same path — a `git checkout` — is another file: read it.
            listed.filter { known[it.path] != it }.forEach { dirty.putIfAbsent(it, generation.incrementAndGet()) }
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
                    // A file deleted since, whose path another file holds now: that one's sites stay.
                    if (hosts[file.path].let { it != null && it != file }) return@synchronized
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
         * Whether the files of [type] are read: one of the [com.ibrahimdans.i18n.Lang]s says it reads a key
         * from them ([com.ibrahimdans.i18n.Lang.indexes]). A language whose plugin is absent registers no
         * `Lang`, so its files are not read.
         */
        fun isCodeType(type: FileType): Boolean = Extensions.LANG.extensionList.any { it.indexes(type) }

        private fun translationValueTypes(): List<FileType> =
            listOfNotNull(JsonFileType.INSTANCE, FileTypeManager.getInstance().findFileTypeByName("YAML"))

        private fun isTranslationValueType(type: FileType): Boolean = type in translationValueTypes()

        private val LOG = logger<CodeUsageIndexService>()

        /** How long a question waits for another one's refresh before checking it was not cancelled. */
        private const val WAIT_MS = 50L

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
