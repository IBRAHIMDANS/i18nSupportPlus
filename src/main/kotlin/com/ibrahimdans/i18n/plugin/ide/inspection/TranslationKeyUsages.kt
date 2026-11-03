package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.IdeI18nRegistry
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.keyScopeOf
import com.ibrahimdans.i18n.plugin.utils.usageScope
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parents
import com.intellij.util.Processor

/**
 * Where the code uses a key of a translation file: the references found on its declaration, the
 * call sites naming it without a reference ([IndirectKeyUsages]), the keys the code builds at
 * runtime that may reach it, and the keys the project keeps. Shared by *Unused translation key* and
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
     * `status.*` keys were underlined as never used, with a *Delete* quick fix one click away —
     * the same defect the tool window's scan carried, and here with no preview standing between
     * the user and the deletion. [DynamicKeyUsages] answers for both places now.
     *
     * The key is composed here rather than read back from the element's own reference: the
     * provider attaches one only when the key already occurs somewhere in the sources, which by
     * definition is never the case for the keys this inspection is about to report.
     */
    fun reachedDynamically(element: PsiElement, heads: MutableMap<String, Set<String>>): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            val project = element.project
            val config = Settings.getInstance(project).config()
            DynamicKeyUsages.isReached(
                keyOf(element, config),
                scopeOf(element),
                PsiSearchHelper.getInstance(project),
                config.nsSeparator,
                config.keySeparator,
                heads,
            )
        }

    /**
     * How many call sites use this key, found by searching the code for its words rather than by
     * its references: the calls naming it, with or without its namespace (`t('common:menu.home')`,
     * `useTranslation('common')` + `t('menu.home')`), a constant holding it, a Paraglide `m.key`,
     * a translation value linking to it; then the calls under a hook's key prefix
     * (`useTranslation('common', { keyPrefix: 'profile' })` + `t('name')`) or with a `{ ns }`
     * option (`t('label', { ns: 'other' })`). The orphan scan has always counted them this way, and
     * every call site the plugin's reference of [count] resolves to is among them — so this is the
     * count shown above a key, and the inspection asks it of a key [count] left at zero.
     *
     * Last, for a key none of these searches finds, the platform's reference search on the key's
     * declaration: nothing of the plugin's resolves onto it (see [count]), but a reference another
     * plugin contributes might. It is kept to the code: the same-named properties of the other
     * locales, which the JSON plugin gives a name reference, were most of its candidates.
     *
     * Two word searches, then a prefix search, then the reference search, each only when the
     * previous one found nothing.
     */
    fun indirectCount(element: PsiElement): Int =
        ReadAction.compute<Int, RuntimeException> {
            val project = element.project
            val config = Settings.getInstance(project).config()
            val key = keyOf(element, config)
            val scope = scopeOf(element)
            IndirectKeyUsages.textCount(
                key, config, scope, PsiSearchHelper.getInstance(project),
                FallbackNamespaces.of(IdeWorkspace(project), config, IdeI18nRegistry),
            )
                .takeIf { it > 0 }
                ?: IndirectKeyUsages.prefixedUsages(project, listOf(key), config)[key].orEmpty().count { it.isIn(scope) }.takeIf { it > 0 }
                ?: platformReferenceCount(element, scope)
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
        var found = 0
        ReferencesSearch.search(declaration, code).forEach(Processor {
            if (it.element !is JsonProperty) found++
            true
        })
        return found
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
        val path = pathOf(element)
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
     * The path of [element] in its file, outermost first, the file's namespace at the front — its
     * own name, unless a technology states it ([TranslationFileScope.statedNamespaceOf]) — the shape
     * [composeKey] expects, and the one the reference assistants build.
     */
    private fun pathOf(element: PsiElement): List<String> =
        element.parents(true).mapNotNull {
            TranslationPsi.nameOf(it) ?: (it as? PsiFile)?.let { file -> TranslationFileScope.statedNamespaceOf(file) ?: file.name.substringBeforeLast(".") }
        }.toList().reversed()
}

/** True when [this] lies in a file [scope] holds: the prefix scan searches the whole project. */
internal fun PsiElement.isIn(scope: GlobalSearchScope): Boolean =
    containingFile?.virtualFile?.let(scope::contains) == true
