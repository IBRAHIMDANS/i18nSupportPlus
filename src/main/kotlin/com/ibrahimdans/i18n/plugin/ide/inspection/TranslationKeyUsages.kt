package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.DynamicKeyUsages
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parents
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Where the code uses a key of a translation file: the references found on its declaration, and
 * the keys the code builds at runtime that may reach it. Shared by *Unused translation key* and
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
            val key = composeKey(
                pathOf(element),
                Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
                config.defaultNamespaces() + Extensions.TECHNOLOGY.extensionList.flatMap { it.cfgNamespaces() },
                false,
                config.firstComponentNs,
            )
            DynamicKeyUsages.isReached(
                key,
                config.searchScope(project),
                PsiSearchHelper.getInstance(project),
                config.nsSeparator,
                config.keySeparator,
                heads,
            )
        }

    /**
     * The path of [element] in its file, outermost first, the file's own name at the front —
     * the shape [composeKey] expects, and the one the reference assistants build.
     */
    private fun pathOf(element: PsiElement): List<String> =
        element.parents(true).mapNotNull {
            when (it) {
                is JsonProperty -> it.name
                is YAMLKeyValue -> it.keyText
                is PsiFile -> it.name.substringBeforeLast(".")
                else -> null
            }
        }.toList().reversed()
}
