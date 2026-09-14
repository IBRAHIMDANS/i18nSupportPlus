package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.factory.ReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.ibrahimdans.i18n.plugin.utils.whenMatches
import com.ibrahimdans.i18n.plugin.utils.whenNotEmpty
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.util.ProcessingContext

/**
 * Data class
 */
data class ReferenceDescriptor(val reference: PropertyReference, val host: PsiElement?)

/**
 * I18nReference to translation
 */
class I18nReference(element: PsiElement, textRange: TextRange, val references: List<ReferenceDescriptor>) : PsiReferenceBase<PsiElement>(element, textRange), PsiPolyVariantReference {

    private fun filterMostResolved(): List<ReferenceDescriptor> {
        val mostResolved = references.maxByOrNull {it.reference.path.size}?.reference?.path?.size
        return references.filter {it.reference.path.size == mostResolved}
    }

    // Distinct: a file both found by name and declared in a configuration would be listed twice.
    private fun findProperties(): List<PsiElement> =
        filterMostResolved().mapNotNull(::targetOf).distinct()

    /**
     * The targets in the translation files of [locale], in no particular order; empty when that locale
     * lacks the key. What Ctrl+click opens, so it does not ask which locale to go to.
     */
    fun targetsIn(locale: String): List<PsiElement> {
        val resolved = filterMostResolved()
        // `en` designates `en-GB` when the project has no plain `en` — see LocaleMatching.
        val label = LocaleMatching.pick(locale, resolved.map { it.reference.localizationSource.localeLabel() })
        return resolved
            .filter { it.reference.localizationSource.localeLabel() == label }
            .mapNotNull(::targetOf)
            .distinct()
    }

    private fun targetOf(item: ReferenceDescriptor): PsiElement? = item.reference.element?.let {
        val res = if (it.isTree()) {
            val parent = it.value().parent
            (parent as? PsiFile) ?: parent.firstChild
        } else it.value()
        item.host ?: res
    }

    override fun resolve(): PsiElement? = multiResolve(false).whenMatches {it.size==1}?.first()?.element

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        findProperties().map(::PsiElementResolveResult).toTypedArray()
}

/**
 * Provides navigation from i18n key to it's value in translation
 */
abstract class ReferenceContributorBase(private val referenceContributor: ReferenceAssistant): PsiReferenceContributor(), CompositeKeyResolver<PsiElement> {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            referenceContributor.pattern(),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    return getReferencesList(element).toTypedArray()
                }
                private fun getReferencesList(element: PsiElement): List<PsiReference> {
                    return referenceContributor.extractKey(element)?.let { fullKey ->
                        val sourceService = element.project.service<LocalizationSourceService>()
                        val pluralSeparator = Settings.getInstance(element.project).config().pluralSeparator
                        sourceService
                            .findSources(fullKey.allNamespaces(), element)
                            .flatMap { source ->
                                resolve(fullKey.compositeKey, source, pluralSeparator).map {
                                    ReferenceDescriptor(it, source.host)
                                }
                            }
                            .filter { it.reference.path.isNotEmpty() }
                            .whenNotEmpty { listOf(I18nReference(element, TextRange(1 + element.text.unQuote().indexOf(fullKey.source), element.textLength - 1), it)) }
                    } ?: emptyList()
                }
            }
        )
    }
}
