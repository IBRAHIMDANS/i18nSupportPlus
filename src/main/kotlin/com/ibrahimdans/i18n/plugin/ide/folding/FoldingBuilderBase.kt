package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

internal data class ElementToReferenceBinding(val psiElement: PsiElement, val reference: PropertyReference)

/**
 * Provides folding mechanism for i18n keys
 */
abstract class FoldingBuilderBase(private val lang: Lang) : FoldingBuilderEx(), DumbAware, CompositeKeyResolver<PsiElement> {

    override fun getPlaceholderText(node: ASTNode): String? = ""

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val config = Settings.getInstance(root.project).config()
        if (!config.foldingEnabled) return arrayOf()
        val foldingProvider = lang.foldingProvider()
        val injectionManager = InjectedLanguageManager.getInstance(root.project)

        // Local to this call on purpose. A set shared across calls on the document, invalidated
        // only when it was edited, emptied every pass replayed without an edit (daemon restart,
        // toggling folding on): all ranges were already claimed and no region came back.
        // Folding builders are looked up per exact language, so the three declarations in
        // plugin.xml never run on the same file; only one call's own repeats need filtering.
        val processedRanges = mutableSetOf<TextRange>()

        return foldingProvider.collectContainers(root)
            .flatMap { container ->
                val (literals, offset) = foldingProvider.collectLiterals(container)
                literals.mapNotNull { literal ->
                    // Prefer the lang extractor so that the namespace from useTranslation('ns') is included
                    // in the RawKey.arguments, enabling correct folding for implicit-namespace keys.
                    // Fall back to a plain RawKey from the literal text for languages (e.g. PHP) whose
                    // extractor does not match folding-provider element types.
                    val rawKey = lang.extractRawKey(literal)
                        ?: RawKey(listOf(KeyElement.literal(literal.text.unQuote())))
                    RawKeyParser(literal.project).parse(rawKey)
                        ?.let { key -> resolve(container, literal, config, key) }
                        ?.let { resolved ->
                            val foldRange = foldingProvider.getFoldingRange(container, offset, resolved.psiElement)
                            // The same call can meet a range twice (e.g. through an injection host)
                            if (!processedRanges.add(foldRange)) return@mapNotNull null
                            // For injected elements (e.g. JS inside Vue <template>), use the injection host's node
                            val node = if (injectionManager.getTopLevelFile(container) !== container.containingFile) {
                                injectionManager.getInjectionHost(container)?.node ?: root.node
                            } else {
                                container.node
                            }
                            FoldingDescriptor(node, foldRange, null,
                                PluralGroup.displayableValue(resolved.reference.element)
                                    ?.value()?.text?.unQuote()?.renderIcu()?.ellipsis(config.foldingMaxLength) ?: "")
                        }
                }
            }
            .sortedBy { it.range.startOffset }
            .toTypedArray()
    }

    private fun resolve(container: PsiElement, element: PsiElement, config: Config, fullKey: FullKey): ElementToReferenceBinding? {
        return element.project.service<LocalizationSourceService>()
            .findSources(fullKey.allNamespaces(), container.project)
            // Through localeLabel, not the parent directory: `locales/en.json` has `locales` as its
            // parent, so the "one file per locale" layout never matched and got no folding at all.
            .filter { it.localeLabel() == config.foldingPreferredLanguage }
            .mapNotNull { resolveCompositeKey(fullKey.compositeKey, it) }
            // A nested plural group holds no value of its own but is displayed through its
            // representative branch, so `isLeaf` is not the test — PluralGroup is.
            .firstOrNull { it.unresolved.isEmpty() && PluralGroup.displayableValue(it.element) != null }
            ?.let { ElementToReferenceBinding(element, it) }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true
}
