package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.ide.usages.CodeUsageIndexService
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.keyScopeOf
import com.ibrahimdans.i18n.plugin.utils.ownerOf
import com.ibrahimdans.i18n.plugin.utils.usageScope
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parents
import com.intellij.util.Processor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the code uses a key of a translation file: the references found on its declaration, the
 * call sites naming it without a reference and the keys the code builds at runtime that may reach
 * it — both read from the index of the code's usages ([CodeUsageIndexService]) —, and the keys the
 * project keeps. Shared by *Unused translation key* and
 * the usage count shown above each key, so both agree on what "used" means.
 *
 * In a monorepo, only the code of the module owning the key's file counts ([ModuleSources.usageScope]):
 * a key `apps/admin` alone uses is unused in `apps/web`'s file.
 */
internal object TranslationKeyUsages : KeyComposer<PsiElement> {

    /**
     * How many code elements the references held by [named] — the element carrying the key's
     * name — resolve to, counting no further than [limit]: the plugin's own translation-to-code
     * reference, one text search of the composed key, attached only to a key written in few files.
     *
     * The platform's `ReferencesSearch` on the declaration used to run here first, for every key
     * and before anything else. It found nothing: the code-to-translation reference resolves onto
     * the tree node of the key, which is not the property the search compares against, and with
     * two locales it resolves to two targets, so `isReferenceTo` is false whatever the node. It
     * cost 50 to 190 ms per key — 19 s for a file of 100 keys on a cold index, 5 s warm, against
     * 0.6 s for this reference — spent resolving every candidate of the key's last word (`title`)
     * across the project. It now runs last, from [indirectCount], for a key nothing else finds.
     */
    fun count(named: PsiElement, limit: Int = Int.MAX_VALUE): Int {
        val found = linkedSetOf<PsiElement>()
        for (reference in named.references) {
            if (found.size >= limit) break
            found += (reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }
                ?: listOfNotNull(reference.resolve())
        }
        return minOf(found.size, limit)
    }

    /**
     * True when some key the code builds at runtime can reach this one.
     *
     * A reference search does not see such a call site: `t(`common:status.${'$'}{kind}`)` writes no
     * name to search for, and the reference it does carry resolves onto the property's *key
     * literal*, which is not what `ReferencesSearch` on the property compares against. So the
     * `status.*` keys were underlined as never used, with a *Delete* quick fix one click away.
     * The index of the code's usages ([CodeUsageIndexService]) answers for the table and here.
     *
     * The key is composed here rather than read back from the element's own reference: the
     * provider attaches one only when the key already occurs somewhere in the sources, which by
     * definition is never the case for the keys this inspection is about to report.
     */
    fun reachedDynamically(element: PsiElement): Boolean = element in reachedDynamically(listOf(element))

    /** Those of [elements] — of one file — that a key the code builds at runtime may reach: one question for them all. */
    fun reachedDynamically(elements: List<PsiElement>): Set<PsiElement> {
        val first = elements.firstOrNull() ?: return emptySet()
        val (keys, module) = keysOf(elements)
        val reached = CodeUsageIndexService.getInstance(first.project).dynamicallyReached(keys.values.toSet(), module)
        return keys.filterValues { it in reached }.keys
    }

    /**
     * How many call sites use this key, read from the index of the code's usages
     * ([CodeUsageIndexService]): the calls naming it, with or without its namespace
     * (`t('common:menu.home')`, `useTranslation('common')` + `t('menu.home')`), a constant holding
     * it, a Paraglide `m.key`, an Angular `@@id`, a translation value linking to it, the calls under a
     * hook's key prefix (`useTranslation('common', { keyPrefix: 'profile' })` + `t('name')`) or reading
     * it as an i18next context — what the table's *Usage* column counts, so the two agree. It is the
     * count shown above a key, and the inspection asks it of a key [count] left at zero.
     *
     * Last, for a key the index does not find, the platform's reference search on the key's
     * declaration: nothing of the plugin's resolves onto it (see [count]), but a reference another
     * plugin contributes might. It is kept to the code: the same-named properties of the other
     * locales, which the JSON plugin gives a name reference, were most of its candidates.
     *
     * At most [MAX_PLATFORM_SEARCHES] of these per question, the others left at what the index says:
     * for a key spelled with a common word (`title`), the search reads every file holding the word —
     * 200 such keys of one file held a highlighting pass for 194 s on the benchmark, 5.5 s once warm.
     * Each count is kept on the key's element until the code changes ([CodeUsageIndexService.codeState]):
     * a pass with no edit since searches nothing again, and the limit only counts the searches run, so
     * the keys left out of one pass are searched by the next ones.
     */
    fun indirectCount(element: PsiElement): Int = indirectCounts(listOf(element)).getValue(element)

    /** [indirectCount] of each of [elements] — of one file —, the index asked once for them all. */
    fun indirectCounts(elements: List<PsiElement>): Map<PsiElement, Int> {
        val first = elements.firstOrNull() ?: return emptyMap()
        val (keys, module) = keysOf(elements)
        val service = CodeUsageIndexService.getInstance(first.project)
        val found = service.usages(keys.values.toSet(), module)
        val state = service.codeState()
        var searches = 0
        return keys.mapValues { (element, key) ->
            found[key]?.count?.takeIf { it > 0 }
                ?: element.getUserData(PLATFORM_COUNT)?.takeIf { it.first == state }?.second
                ?: if (searches++ >= MAX_PLATFORM_SEARCHES) 0
                else ReadAction.compute<Int, RuntimeException> { platformReferenceCount(element, scopeOf(element)) }
                    .also { element.putUserData(PLATFORM_COUNT, state to it) }
        }
    }

    /** The platform's count of a key's element, with the [CodeUsageIndexService.codeState] it was searched in. */
    private val PLATFORM_COUNT = Key.create<Pair<Any, Int>>("i18n.platformReferenceCount")

    /** How many keys of one question the platform's reference search is run for, when the index finds none. */
    internal const val MAX_PLATFORM_SEARCHES = 20

    /**
     * Whether a highlighting pass may count now: the index of the code's usages is built. Until it is,
     * it is built in the background and the pass shows nothing — a key counted from a half-read
     * project would be reported unused while the code uses it.
     */
    fun readyForHighlighting(project: Project): Boolean = CodeUsageIndexService.getInstance(project).readyForHighlighting()

    /** The key each of [elements] declares, and the module owning their file, whose code alone counts. */
    private fun keysOf(elements: List<PsiElement>): Pair<Map<PsiElement, String>, ModuleConfig?> =
        ReadAction.compute<Pair<Map<PsiElement, String>, ModuleConfig?>, RuntimeException> {
            val project = elements.first().project
            val config = Settings.getInstance(project).config()
            elements.associateWith { keyOf(it, config) } to ModuleSources.ownerOf(project, elements.first().containingFile?.virtualFile)
        }

    /**
     * The references the platform finds on the declaration of [element] — the property or key-value
     * carrying its name — outside the files of the declaration's own type. A reference held by a
     * JSON property is not a use by the code: the JSON plugin gives every property a name reference
     * that resolves to *every* same-named property of *any* JSON file.
     */
    private fun platformReferenceCount(element: PsiElement, scope: GlobalSearchScope): Int {
        val declaration = element.parent ?: element
        val fileType = element.containingFile?.fileType
        val code = if (fileType == null) scope
            else scope.intersectWith(GlobalSearchScope.notScope(GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(element.project), fileType)))
        // Counted atomically: the search calls the processor from several threads at once.
        val found = AtomicInteger()
        ReferencesSearch.search(declaration, code).forEach(Processor {
            if (it.element !is JsonProperty) found.incrementAndGet()
            true
        })
        return found.get()
    }

    /**
     * True when the project's [KeepList] declares this key used: a key no code names — received
     * from an API, stored elsewhere — that must never be reported nor offered for deletion.
     */
    fun kept(element: PsiElement): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            val config = Settings.getInstance(element.project).config()
            val keepList = KeepList.of(config)
            !keepList.isEmpty() && keepList.matches(keyOf(element, config))
        }

    /** Where the code using [element]'s key is searched: its module's, in a monorepo. */
    private fun scopeOf(element: PsiElement): GlobalSearchScope =
        ModuleSources.usageScope(element.project, element.containingFile?.virtualFile)

    /** The full key [element] declares, written the way the code would name it. */
    fun keyOf(element: PsiElement, config: Config): String {
        val path = pathOf(element, config)
        // A scope file (Transloco) is named after its locale: its keys are written `scope.key`.
        val scope = element.containingFile?.let(::keyScopeOf)
        return composeKey(
            if (scope != null) listOf(scope) + path.drop(1) else path,
            Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
            config.defaultNamespaces() + Extensions.TECHNOLOGY.extensionList.flatMap { it.cfgNamespaces() },
            false,
            config.firstComponentNs || scope != null,
        )
    }

    /**
     * The path of [element] in its file, outermost first, the file's namespace at the front — the shape
     * [composeKey] expects, and the one the reference assistants build.
     *
     * The namespace is the one the tool window reads the file under ([TranslationDataLoader.extractNamespace]):
     * a technology's stated one, a format's (a Laravel package override), the default one for a file named after
     * its locale, its own name otherwise. A file named after its locale (`messages/en.json`) once lent its name to
     * every key, `en:common.save`: the table and the code read `common.save`, so a key reached only through
     * `` t(`common.${'$'}{x}`) `` or kept as `common.*` was reported unused here while the table counted it.
     */
    private fun pathOf(element: PsiElement, config: Config): List<String> =
        element.parents(true).mapNotNull {
            TranslationPsi.nameOf(it) ?: (it as? PsiFile)?.let { file -> namespaceOf(file, config) }
        }.toList().reversed()

    private fun namespaceOf(file: PsiFile, config: Config): String =
        TranslationFileScope.sourceOf(file)?.let { TranslationDataLoader.extractNamespace(it, config.defaultNamespaces().first()) }
            ?: file.name.substringBeforeLast(".")
}
