package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal data class ElementToReferenceBinding(val psiElement: PsiElement, val reference: PropertyReference)

/**
 * Provides folding mechanism for i18n keys
 */
abstract class FoldingBuilderBase(private val lang: Lang) : FoldingBuilderEx(), DumbAware, CompositeKeyResolver<PsiElement> {

    companion object {
        // Tracks TextRanges already claimed by a previous builder call for the same host document.
        // Prevents duplicate folding descriptors when multiple language builders (TypeScript, JavaScript,
        // TypeScript JSX, Vue) are each invoked by IntelliJ for the same underlying content.
        // The Long is the document modificationStamp so the set is invalidated on each edit.
        private val FOLDING_CACHE_KEY = Key.create<Pair<Long, MutableSet<TextRange>>>("i18n.folding.cache")
    }

    override fun getPlaceholderText(node: ASTNode): String? = ""

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val config = Settings.getInstance(root.project).config()
        if (!config.foldingEnabled) return arrayOf()
        val foldingProvider = lang.foldingProvider()
        val injectionManager = InjectedLanguageManager.getInstance(root.project)

        // Resolve the host document to share the claimed-ranges set across all language builder calls
        val hostDoc = injectionManager.getTopLevelFile(root).viewProvider.document ?: document
        val processedRanges = getOrResetProcessedRanges(hostDoc)

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
                            // Skip if another language builder already claimed this range
                            if (!processedRanges.add(foldRange)) return@mapNotNull null
                            // For injected elements (e.g. JS inside Vue <template>), use the injection host's node
                            val node = if (injectionManager.getTopLevelFile(container) !== container.containingFile) {
                                injectionManager.getInjectionHost(container)?.node ?: root.node
                            } else {
                                container.node
                            }
                            FoldingDescriptor(node, foldRange, null,
                                resolved.reference.element?.value()?.text?.unQuote()?.ellipsis(config.foldingMaxLength) ?: "")
                        }
                }
            }
            .sortedBy { it.range.startOffset }
            .toTypedArray()
    }

    private fun getOrResetProcessedRanges(document: Document): MutableSet<TextRange> {
        synchronized(document) {
            val modStamp = document.modificationStamp
            val cached = document.getUserData(FOLDING_CACHE_KEY)
            if (cached != null && cached.first == modStamp) return cached.second
            val newSet: MutableSet<TextRange> = Collections.newSetFromMap(ConcurrentHashMap())
            document.putUserData(FOLDING_CACHE_KEY, Pair(modStamp, newSet))
            return newSet
        }
    }

    private fun resolve(container: PsiElement, element: PsiElement, config: Config, fullKey: FullKey): ElementToReferenceBinding? {
        return element.project.service<LocalizationSourceService>()
            .findSources(fullKey.allNamespaces(), container.project)
            .filter {
                it.parent == config.foldingPreferredLanguage
            }
            .map { resolveCompositeKey(fullKey.compositeKey, it)}
            .firstOrNull { it.unresolved.isEmpty() && it.element?.isLeaf() == true }
            ?.let { ElementToReferenceBinding(element, it) }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true
}
