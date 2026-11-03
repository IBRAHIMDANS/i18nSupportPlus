package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideOutdir
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideTechnology
import com.ibrahimdans.i18n.extensions.technology.typesafei18n.TypesafeI18nTechnology
import com.ibrahimdans.i18n.plugin.ide.references.translation.LinkedMessages
import com.ibrahimdans.i18n.plugin.ide.references.translation.ReferencesAccumulator
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.ContextKey
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.keyScopesOf
import com.intellij.json.JsonFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The usages of a key that leave no reference on it, found by searching the code for the key's
 * words: a call under a hook's namespace or key prefix, a `{ ns }` option. Shared by the orphan
 * scan behind *Scan Orphans* and *Cleanup Unused Keys*, and by *Unused translation key* and the
 * usage count above each key, so that all of them agree on what "used" means.
 */
internal object IndirectKeyUsages {

    /**
     * Where the code writes a key, as the word index classifies it: a string (`t('menu.title')`,
     * `$localize`:@@id:…``, a YAML value), the text or an attribute of a template (`<p translate>menu.title</p>`,
     * `i18n="@@id"`, `<Trans i18nKey="…">`), or a language embedded in another (the `{{ $t('…') }}` of
     * a Vue template, the `__()` of a Blade view). Identifiers and comments are left out: the last
     * segment of a key — `title`, `label`, `name` — is also an identifier of nearly every source
     * file, and each candidate file of a word search is parsed to find the word in it.
     *
     * Two things the index files as code are read elsewhere: a Paraglide or typesafe-i18n key, which
     * is a member name ([keySearchContext]), and a JSON value ([translationValues]).
     */
    val KEY_SEARCH_CONTEXT: Short =
        (UsageSearchContext.IN_STRINGS.toInt() or UsageSearchContext.IN_PLAIN_TEXT.toInt() or UsageSearchContext.IN_FOREIGN_LANGUAGES.toInt()).toShort()

    /**
     * [KEY_SEARCH_CONTEXT], with identifiers back in a project where the code names a key as one:
     * Paraglide's `m.hello()` and typesafe-i18n's `LL.hello()`. Everything, for a scope of no project.
     */
    fun keySearchContext(project: Project?): Short = when {
        project == null -> UsageSearchContext.ANY
        writesKeysAsIdentifiers(project) -> (KEY_SEARCH_CONTEXT.toInt() or UsageSearchContext.IN_CODE.toInt()).toShort()
        else -> KEY_SEARCH_CONTEXT
    }

    /**
     * True when [project] holds an inlang project, a compiled Paraglide output folder ([ParaglideOutdir]),
     * a typesafe-i18n output folder, or a source file importing a module whose specifier names one of the
     * two ([importsIdentifierKeyLibrary]) — a component importing `../paraglide/messages.js` is enough,
     * the output folder may not be in the project. Erring on the side of a true answer costs identifiers
     * back in the searches, never a usage.
     *
     * Kept until a file is added, moved or removed, or until a file starts or stops importing one of the
     * libraries ([IdentifierKeyImports]): a keystroke elsewhere leaves it alone, where the PSI-wide count
     * it used to follow redid these index lookups after every one.
     */
    private fun writesKeysAsIdentifiers(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val found = ParaglideTechnology.projects(project).isNotEmpty() ||
                TypesafeI18nTechnology.outputFolders(project).isNotEmpty() ||
                FilenameIndex.getVirtualFilesByName("runtime.js", scope).any { "/node_modules/" !in it.path && ParaglideOutdir.contains(it) } ||
                importsIdentifierKeyLibrary(project, scope)
            CachedValueProvider.Result.create(
                found,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                IdentifierKeyImports.getInstance(project),
            )
        }

    /** Whether a source file of [scope], outside `node_modules`, imports Paraglide's or typesafe-i18n's code. */
    private fun importsIdentifierKeyLibrary(project: Project, scope: GlobalSearchScope): Boolean {
        val cache = CacheManager.getInstance(project)
        val documents = FileDocumentManager.getInstance()
        return IDENTIFIER_KEY_WORDS.asSequence()
            .flatMap { word -> cache.getVirtualFilesWithWord(word, UsageSearchContext.ANY, scope, true).asSequence() }
            .distinct()
            .filter { "/node_modules/" !in it.path && !it.fileType.isBinary }
            .any { file -> importsIdentifierKeyLibrary(documents.getCachedDocument(file)?.charsSequence ?: LoadTextUtil.loadText(file)) }
    }

    /** The words of the index leading to an import of [IDENTIFIER_KEY_LIBRARIES]: `typesafe-i18n` is two of them. */
    private val IDENTIFIER_KEY_WORDS = listOf("paraglide", "typesafe")

    /** What the specifier of an import of Paraglide's or typesafe-i18n's code holds. */
    private val IDENTIFIER_KEY_LIBRARIES = listOf("paraglide", "typesafe-i18n")

    /** The specifier of an `import … from '…'`, `import('…')`, `export … from '…'` or `require('…')`. */
    private val IMPORT_SPECIFIER = Regex("""(?:\bfrom|\bimport\s*\(?|\brequire\s*\()\s*['"`]([^'"`\n]+)['"`]""")

    /**
     * Whether [text] imports a module whose specifier names Paraglide or typesafe-i18n: `$lib/paraglide/messages`,
     * `../paraglide/messages.js`, `typesafe-i18n/react`. A comment or a word naming the library is not an import.
     */
    fun importsIdentifierKeyLibrary(text: CharSequence): Boolean {
        if (IDENTIFIER_KEY_LIBRARIES.none { text.contains(it) }) return false
        return IMPORT_SPECIFIER.findAll(text).any { match -> IDENTIFIER_KEY_LIBRARIES.any { it in match.groupValues[1] } }
    }

    /**
     * The JSON and YAML files of [scope]: where a translation value linking to a key (`"@:menu.title"`)
     * is found. The index files a JSON string as code, so the search of the code's strings never
     * reaches it; these files are few, and they are the only ones such a value lives in.
     */
    private fun translationValues(scope: GlobalSearchScope): GlobalSearchScope {
        val types = listOfNotNull(JsonFileType.INSTANCE, FileTypeManager.getInstance().findFileTypeByName("YAML"))
        return GlobalSearchScope.getScopeRestrictedByFileTypes(scope, *types.toTypedArray())
    }

    /**
     * How many call sites name [key] outright, found by a text search of the key rather than by
     * references: `t('menu.profile')` under `useTranslation('navigation')`, or
     * `t('label', { ns: 'other' })`, both of which leave no reference on the key itself. A call under
     * a hook counts for a key of the [fallbacks] — i18next's `fallbackNS` — too.
     */
    fun textCount(
        key: String,
        config: Config,
        searchScope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        fallbacks: List<String> = emptyList(),
    ): Int {
        val (calls, linked) = textSearch(key, config, searchScope, searchHelper, fallbacks)
        // Distinct, because the same call site is reported once per word searched: a key
        // carrying a namespace matches both `navigation:menu.profile` and `menu.profile`,
        // and counting it twice inflated every usage of every prefixed key.
        return calls.distinct().size + linked.size
    }

    /** The elements [textCount] counts: the call sites naming [key], then the values linking to it. */
    fun textUsages(
        key: String,
        config: Config,
        searchScope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        fallbacks: List<String> = emptyList(),
    ): Set<PsiElement> {
        val (calls, linked) = textSearch(key, config, searchScope, searchHelper, fallbacks)
        return calls.toSet() + linked
    }

    private fun textSearch(
        key: String,
        config: Config,
        searchScope: GlobalSearchScope,
        searchHelper: PsiSearchHelper,
        fallbacks: List<String>,
    ): Pair<Collection<PsiElement>, Set<PsiElement>> {
        val query = usageQuery(key, config.pluralSeparator)
        val accumulator = ReferencesAccumulator(
            query.bareKey,
            Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
            // A key carrying no namespace lives in a default one, which is also what a
            // call site writing no namespace works under.
            query.namespace?.let { listOf(it) } ?: config.defaultNamespaces(),
            fallbacks,
        )
        val process = accumulator.process()
        val context = keySearchContext(searchScope.project)
        // The same words find the translation values linking to the key (`"@:menu.profile"`), in
        // the translation files alone, whatever the index files their strings as. The search calls
        // the processor from several threads at once: a plain set lost hits.
        val linked: MutableSet<PsiElement> = ConcurrentHashMap.newKeySet()
        val valueScope = translationValues(searchScope)
        for (word in query.words) {
            searchHelper.processElementsWithWord(process, searchScope, word, context, true)
            searchHelper.processElementsWithWord(
                { element, _ ->
                    if (element !in linked && linksTo(element, key, config)) linked += element
                    true
                },
                valueScope, word, UsageSearchContext.ANY, true
            )
        }
        return accumulator.entries() to linked
    }

    /**
     * Usages of [keys] through a hook key prefix — react-i18next's `keyPrefix`, next-intl's
     * `useTranslations('Home')` and `await getTranslations('Home')`, Transloco's `read` — keyed by
     * the keys found, absent ones left out. A call under a `read` naming a scope ([keyScopeOf])
     * counts for the scope key, spelled `scope.key`, though its literal writes no prefix.
     *
     * The call site writes only the key's last levels (`t('title')` for `header.title`), so the
     * last segment is searched as a word, and a literal counts only when the key it resolves to —
     * extracted and parsed the way the annotator does, prefix applied — has exactly this path and
     * works in this key's namespace. Literals without a prefix are left to the text scan, which
     * already counted them ([textCount]): counting them here too would double every usage.
     */
    fun prefixedCounts(project: Project, keys: List<String>, config: Config): Map<String, Int> =
        prefixedUsages(project, keys, config).mapValues { it.value.size }

    /**
     * The literals [prefixedCounts] counts, keyed the same way — and the calls reading a key as an
     * i18next context variant: `t('friend', { context: gender })` uses `friend_male` and
     * `friend_female`, `{ context: 'male' }` the first only. Such a call never writes the variant's
     * name, so the text scan cannot find it, prefix or not.
     */
    fun prefixedUsages(project: Project, keys: List<String>, config: Config): Map<String, Set<PsiElement>> {
        if (keys.isEmpty()) return emptyMap()
        val defaultNamespaces = config.defaultNamespaces()
        val languages = Extensions.LANG.extensionList
        val parser = RawKeyParser(project)
        val keyScopes = keyScopesOf(project)
        // Filled from the search's threads at once: a plain map and sets lost hits under load.
        val found = ConcurrentHashMap<String, MutableSet<PsiElement>>()

        val byWord = keys.flatMap { key -> wantedPaths(key, config) }.groupBy { it.segments.last() }
        for ((word, wanted) in byWord) {
            if (word.isBlank()) continue
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                { element, _ ->
                    val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) } ?: return@processElementsWithWord true
                    val fullKey = languages.firstNotNullOfOrNull { it.extractRawKey(literal) }
                        ?.let { parser.parse(it) }
                        ?: return@processElementsWithWord true
                    val scope = keyScopeOf(fullKey, keyScopes)
                    val prefixed = fullKey.keyPrefix.isNotEmpty() || scope != null
                    if (!prefixed && fullKey.context == null) return@processElementsWithWord true
                    // A scope key is spelled `admin.title`: the scope is its first segment, not a namespace.
                    val path = listOfNotNull(scope) + fullKey.compositeKey.map { it.text }
                    val namespaces = if (scope != null) emptyList() else fullKey.lookupNamespaces()
                    for ((key, segments, context) in wanted) {
                        if (path != segments) continue
                        if (if (context == null) !prefixed else !ContextKey.reads(fullKey.context, context)) continue
                        val namespace = KeySpelling.namespaceOf(key)
                        val inNamespace = if (namespace == null) namespaces.isEmpty() || namespaces.any { it in defaultNamespaces }
                            else namespaces.isEmpty() || namespace in namespaces
                        if (inNamespace) found.getOrPut(key) { ConcurrentHashMap.newKeySet() } += literal
                    }
                    true
                },
                config.searchScope(project),
                word,
                keySearchContext(project),
                true
            )
        }
        return found
    }

    /** The word [prefixedUsages] searches on behalf of [key]: the last segment of its own path. */
    fun prefixWordOf(key: String, config: Config): String =
        wantedPaths(key, config).first().segments.lastOrNull().orEmpty()

    /** A call path [prefixedUsages] counts for [key]; with a [context], only a call reading that context. */
    private data class WantedPath(val key: String, val segments: List<String>, val context: String? = null)

    /**
     * The paths a call naming [key] may write: the key's own, and, when its last segment is spelled
     * as a context variant (`friend_male`, plural suffix stripped), the base under that context.
     * Not when i18next's context separator is also the key separator: the segments then hold no
     * variant to tell apart.
     */
    private fun wantedPaths(key: String, config: Config): List<WantedPath> {
        val segments = KeySpelling.segmentsOf(PluralKey.stripSuffix(key, config.pluralSeparator), config)
        val own = WantedPath(key, segments)
        if (config.keySeparator == ContextKey.SEPARATOR) return listOf(own)
        val (base, context) = segments.lastOrNull()?.let(ContextKey::split) ?: return listOf(own)
        return listOf(own, WantedPath(key, segments.dropLast(1) + base, context))
    }

    /**
     * The key scope a call site works in without writing it: Transloco's `t('title')` under
     * `*transloco="let t; read: 'admin'"`, whose only namespace is the scope `admin`. A key written
     * with its scope (`admin.title`) carries it as [FullKey.ns] and is left to the text scan.
     */
    private fun keyScopeOf(fullKey: FullKey, keyScopes: Set<String>): String? =
        fullKey.namespaces?.singleOrNull()?.takeIf { fullKey.ns == null && it in keyScopes }

    /**
     * True when [element] is a translation value whose vue-i18n linked message names [key]:
     * `"Click @:common.save"` uses `common.save`, though no code does. Each link is resolved the way
     * its reference is ([LinkedMessages]), so a link counts only for the key it actually reaches —
     * and never in a module whose preset names another framework.
     *
     * Its targets are read in every locale ([LinkedMessages.allTargetsOf]): `fr.json`'s link uses the
     * key of `en.json` too, which *Clean Up* deletes from every locale at once.
     */
    private fun linksTo(element: PsiElement, key: String, config: Config): Boolean =
        LinkedMessages.linksOf(element).any { link ->
            LinkedMessages.allTargetsOf(element, link.key).any { target -> TranslationKeyUsages.keyOf(target, config) == key }
        }

    /** What [textCount] searches the sources for, on behalf of one key. */
    data class UsageQuery(val bareKey: String, val words: List<String>, val namespace: String? = null)

    /**
     * The search terms standing for [key], and the prefix a hit has to start with.
     *
     * Two forms are searched: the full key (`navigation:menu.profile`), so an explicitly
     * namespaced call matches, and the bare one (`menu.profile`), for the implicit namespace
     * of `useTranslation('navigation') + t('menu.profile')`.
     *
     * Both are stripped of their plural suffix first. A key read from a translation file is a
     * *form* — `…addTrustee.description_other` — while the source only ever writes the key
     * i18next appends the suffix to, `t('…addTrustee.description', { count })`. Searching the
     * form found nothing by construction, so every pluralized key was reported as an orphan,
     * and *Cleanup unused keys* offered to delete a key that was in use.
     */
    fun usageQuery(key: String, pluralSeparator: String): UsageQuery {
        val colonIdx = key.indexOf(':')
        val namespace = if (colonIdx > 0) key.substring(0, colonIdx) else null
        val bareKey = PluralKey.stripSuffix(
            if (colonIdx > 0) key.substring(colonIdx + 1) else key,
            pluralSeparator,
        )
        return UsageQuery(
            bareKey,
            listOfNotNull(namespace?.let { "$it:$bareKey" }, bareKey),
            namespace,
        )
    }
}


/**
 * Counts the edits that make a file start or stop importing Paraglide's or typesafe-i18n's code
 * ([IndirectKeyUsages.importsIdentifierKeyLibrary]), and those alone: what [IndirectKeyUsages.keySearchContext]
 * depends on besides the files present. Each edited file's last answer is kept, so typing in a component that
 * already imports the library, or in one that never will, counts nothing.
 */
@Service(Service.Level.PROJECT)
internal class IdentifierKeyImports(project: Project) : ModificationTracker, Disposable {

    private val changes = SimpleModificationTracker()

    /** Whether each file edited since the project opened imports one of the libraries. */
    private val imports = ConcurrentHashMap<VirtualFile, Boolean>()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
            override fun childMoved(event: PsiTreeChangeEvent) = onChange(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
        }, this)
    }

    override fun getModificationCount(): Long = changes.modificationCount

    /** A change inside no file is structural: `VFS_STRUCTURE_MODIFICATIONS` counts it already. */
    private fun onChange(event: PsiTreeChangeEvent) {
        val file = event.file ?: return
        val virtualFile = file.viewProvider.virtualFile
        val now = IndirectKeyUsages.importsIdentifierKeyLibrary(file.viewProvider.contents)
        val before = imports.put(virtualFile, now)
        // A file seen for the first time counts only when it imports: the answer may have been false.
        if (if (before == null) now else before != now) changes.incModificationCount()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): IdentifierKeyImports = project.service()
    }
}
