package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parents
import com.intellij.util.Processor

/**
 * Where the code uses a key of a translation file: the references found on its declaration, the
 * keys the code builds at runtime that may reach it, and the keys the project keeps. Shared by *Unused translation key* and
 * the usage count shown above each key, so both agree on what "used" means.
 */
internal object TranslationKeyUsages : KeyComposer<PsiElement> {

    /**
     * How many places of the code use the key declared by [declaration], counting no further than
     * [limit]: the references a search finds on [declaration], and the code elements the
     * references held by [named] — the element carrying the key's name — resolve to.
     */
    fun count(declaration: PsiElement, named: PsiElement, limit: Int = Int.MAX_VALUE): Int {
        val found = linkedSetOf<PsiElement>()
        ReferencesSearch.search(declaration).forEach(Processor { found += it.element; found.size < limit })
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
                config.searchScope(project),
                PsiSearchHelper.getInstance(project),
                config.nsSeparator,
                config.keySeparator,
                heads,
            )
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

    /** The full key [element] declares, written the way the code would name it. */
    fun keyOf(element: PsiElement, config: Config): String {
        val path = pathOf(element)
        // A scope file (Transloco) is named after its locale: its keys are written `scope.key`.
        val scope = element.containingFile?.let { file -> Extensions.TECHNOLOGY.extensionList.firstNotNullOfOrNull { it.keyScopeOf(file) } }
        return composeKey(
            if (scope != null) listOf(scope) + path.drop(1) else path,
            Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
            config.defaultNamespaces() + Extensions.TECHNOLOGY.extensionList.flatMap { it.cfgNamespaces() },
            false,
            config.firstComponentNs || scope != null,
        )
    }

    /**
     * The path of [element] in its file, outermost first, the file's own name at the front —
     * the shape [composeKey] expects, and the one the reference assistants build.
     */
    private fun pathOf(element: PsiElement): List<String> =
        element.parents(true).mapNotNull {
            TranslationPsi.nameOf(it) ?: (it as? PsiFile)?.name?.substringBeforeLast(".")
        }.toList().reversed()
}
