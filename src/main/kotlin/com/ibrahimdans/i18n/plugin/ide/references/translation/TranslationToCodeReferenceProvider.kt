package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.KeyComposer
import com.ibrahimdans.i18n.plugin.tree.Separators
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import java.util.Collections.synchronizedList
@Service
internal class TranslationToCodeReferenceProvider : KeyComposer<PsiElement> {

    /**
     * @param element PsiElement to get references of.
     * @param textRange TextRange to highlight
     */
    fun getReferences(element: PsiElement, textRange: TextRange, parents: List<String>): List<PsiReference> {
        val project = element.project
        val config = Settings.getInstance(project).config()
        val key = composeKey(
            parents,
            Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator),
            config.defaultNamespaces() + Extensions.TECHNOLOGY.extensionList.flatMap {it.cfgNamespaces()},
            false,
            config.firstComponentNs
        )
        @Suppress("DEPRECATION")
        if (PsiSearchHelper.SearchCostResult.FEW_OCCURRENCES ==
                PsiSearchHelper.getInstance(project).isCheapEnoughToSearch(key, config.searchScope(project), null)
        ) {
            return listOf(TranslationToCodeReference(element, textRange, key))
        }
        return emptyList()
    }
}

/**
 * Accumulates the call sites naming [key].
 *
 * [key] carries no namespace: what the call site writes before the namespace separator is
 * compared with [namespaces] instead, so an accumulator can tell `navigation:menu.profile`
 * from `common:menu.profile`. An empty [namespaces] switches that check off, which is what a
 * caller that cannot know the namespace passes.
 */
class ReferencesAccumulator(
    private val key: String,
    private val separators: Separators = Separators(":", ".", "."),
    private val namespaces: List<String> = emptyList(),
) {

    private val res = synchronizedList(mutableListOf<PsiElement>())

    /**
     * Processing function for PsiSearchHelper
     */
    fun process() = {
        entry: PsiElement, _:Int ->
        val languages = Extensions.LANG.extensionList
        val text = entry.text.unQuote()
        if (namesKey(text) && underExpectedNamespace(entry, text, languages)) {
            val entryRef = languages.stream().map {lang -> lang.resolveLiteral(entry)}.filter {it!=null}.findFirst()
            entryRef.ifPresent { res.add(it) }
        }
        true
    }

    /**
     * True when [text] names [key] — its own namespace, if it writes one, set aside.
     *
     * The match is a prefix one on purpose: a key naming an *object* (`menu`) is reached by
     * every call under it (`menu.home`), which is how navigation from a parent node finds its
     * children's call sites. What it must not do is cross a segment: `menu.home` used to match
     * `menu.homePage` and `menu.homeIcon`, so the *Usage* column counted calls to neighbouring
     * keys and a genuinely dead key could be reported as used. Whatever follows the prefix is
     * now required to start a new segment — or to be nothing at all.
     *
     * Only the key separator opens a segment. A plural suffix is deliberately *not* a
     * boundary: `processElementsWithWord` matches whole words, so a call site spelling the
     * form out (`t('cart.item_other')`) never reaches this filter in the first place — while
     * accepting the configured plural separator here would let `menu.home-page` back in
     * through the default `-`, which is the very hole this closes.
     */
    private fun namesKey(text: String): Boolean {
        val written = if (text.contains(separators.ns)) text.substringAfter(separators.ns) else text
        if (!written.startsWith(key)) return false
        val rest = written.substring(key.length)
        return rest.isEmpty() || rest.startsWith(separators.key)
    }

    /**
     * True when the call site works in one of [namespaces].
     *
     * The namespace it writes wins; with none written, the one its `useTranslation` declares
     * is read through [Lang.extractRawKey] — the same extraction annotation and completion go
     * through. A call site declaring nothing at all is accepted: reporting a live key as an
     * orphan is the error worth avoiding, and a file whose hook cannot be resolved would
     * otherwise lose every usage it holds.
     */
    private fun underExpectedNamespace(entry: PsiElement, text: String, languages: List<Lang>): Boolean {
        if (namespaces.isEmpty()) return true
        if (text.contains(separators.ns)) return text.substringBefore(separators.ns) in namespaces
        val declared = languages.firstNotNullOfOrNull { it.extractRawKey(entry)?.arguments?.ifEmpty { null } }
        return declared == null || declared.any { it in namespaces }
    }

    /**
     * Returns collected entries
     */
    fun entries(): Collection<PsiElement> = res
}

/**
 * Reference to key usage for translation file
 */
class TranslationToCodeReference(element: PsiElement, textRange: TextRange, val composedKey: String) : PsiReferenceBase<PsiElement>(element, textRange), PsiPolyVariantReference {

    /**
     * Finds usages of json translation
     */
    fun findRefs(): Collection<PsiElement> {
        return ProgressManager.getInstance().runProcess (
            Computable {
                val project = element.project
                val config = Settings.getInstance(project).config()
                val separators = Separators(config.nsSeparator, config.keySeparator, config.pluralSeparator)
                // The composed key carries its namespace only when it is not a default one;
                // the accumulator wants the two apart, and a key composed without a namespace
                // is one of the defaults — which is what a bare call site works under.
                val namespace = composedKey.substringBefore(separators.ns, "")
                val referencesAccumulator = ReferencesAccumulator(
                    composedKey.substringAfter(separators.ns),
                    separators,
                    if (namespace.isEmpty()) config.defaultNamespaces() else listOf(namespace),
                )
                PsiSearchHelper.getInstance(project).processElementsWithWord(
                    referencesAccumulator.process(),
                    config.searchScope(project),
                    composedKey,
                    UsageSearchContext.ANY,
                    true
                )
                referencesAccumulator.entries()
            },
            DaemonProgressIndicator()
        )
    }

    override fun resolve(): PsiElement? = multiResolve(false).firstOrNull()?.element

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
            findRefs()
                    .map {property -> PsiElementResolveResult(property) }
                    .toTypedArray()
}
