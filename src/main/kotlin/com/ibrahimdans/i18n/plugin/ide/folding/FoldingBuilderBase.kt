package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.preview.PreviewLocaleSwitcher
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PropertyReference
import com.ibrahimdans.i18n.plugin.utils.InlangMessageRenderer
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil

internal data class ElementToReferenceBinding(val psiElement: PsiElement, val reference: PropertyReference<PsiElement>)

/**
 * Provides folding mechanism for i18n keys
 */
abstract class FoldingBuilderBase(private val lang: Lang) : FoldingBuilderEx(), DumbAware, CompositeKeyResolver<PsiElement> {

    override fun getPlaceholderText(node: ASTNode): String? = ""

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val config = Settings.getInstance(root.project).config()
        if (!config.foldingEnabled) return arrayOf()
        return keyDescriptors(root, config)
    }

    /** The keys of [root] folding would show, whether or not folding is on. */
    private fun keyDescriptors(root: PsiElement, config: Config): Array<FoldingDescriptor> {
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
                    RawKeyParser(literal.project).parse(rawKey, literal)
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
                                    ?.value()?.text?.let(InlangMessageRenderer::preview)
                                    ?.unQuote()?.renderIcu()?.ellipsis(config.foldingMaxLength) ?: "")
                        }
                }
            }
            .sortedBy { it.range.startOffset }
            .toTypedArray()
    }

    private fun resolve(container: PsiElement, element: PsiElement, config: Config, fullKey: FullKey): ElementToReferenceBinding? {
        val sources = element.project.service<LocalizationSourceService>().findReadSources(fullKey, element)
        // Through localeLabel, not the parent directory: `locales/en.json` has `locales` as its
        // parent, so the "one file per locale" layout never matched and got no folding at all.
        // And through LocaleMatching, so `en` finds `en-GB` when there is no plain `en`. The preview
        // locale first, as the inlay hints and the hover read it, so the status bar switch folds too.
        val locale = LocaleMatching.pick(PreviewLocaleSwitcher.effective(config), sources.map { it.localeLabel() })
        return sources
            .filter { it.localeLabel() == locale }
            .mapNotNull { resolveCompositeKey(fullKey.compositeKey, it) }
            // A nested plural group holds no value of its own but is displayed through its
            // representative branch, so `isLeaf` is not the test — PluralGroup is.
            .firstOrNull { it.unresolved.isEmpty() && PluralGroup.displayableValue(it.element) != null }
            ?.let { ElementToReferenceBinding(element, it) }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    companion object {

        /**
         * Brings the key regions of the editors already open of [project] in line with folding
         * just turned on ([fold]) or off.
         *
         * Not left to the daemon, which gets both wrong in an open editor. Only an editor's first
         * folding pass applies [isCollapsedByDefault]: a region that appears later is added
         * expanded (`UpdateFoldRegionsOperation.shouldExpandNewRegion`). And a folded region the
         * builders no longer return is kept as long as it is folded. So the regions are updated
         * here, as the daemon's folding pass does it: on turning on, those of the keys are then
         * collapsed; on turning off, they are expanded first, so that the update drops them. Only
         * the keys: the user's own folding is left as it was, and the key under the caret is not
         * collapsed, as the platform leaves it open on an editor's first pass too.
         */
        fun refreshOpenEditors(project: Project, fold: Boolean) {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { editor ->
                    ReadAction.nonBlocking<Pair<Runnable?, Set<TextRange>>> {
                        val update = CodeFoldingManager.getInstance(project).updateFoldRegionsAsync(editor, false)
                        update to keyRanges(project, editor.document)
                    }
                        .withDocumentsCommitted(project)
                        .expireWhen { editor.isDisposed || project.isDisposed }
                        .finishOnUiThread(ModalityState.defaultModalityState()) { (update, ranges) ->
                            if (fold) {
                                update?.run()
                                setExpanded(editor, ranges, expanded = false)
                            } else {
                                setExpanded(editor, ranges, expanded = true)
                                update?.run()
                            }
                        }
                        .submit(AppExecutorUtil.getAppExecutorService())
                }
        }

        /** The ranges this plugin folds, or would fold, in [document]'s file, every language of it. */
        private fun keyRanges(project: Project, document: Document): Set<TextRange> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return emptySet()
            val config = Settings.getInstance(project).config()
            val provider = file.viewProvider
            return provider.languages.flatMapTo(mutableSetOf()) { language ->
                val root = provider.getPsi(language) ?: return@flatMapTo emptyList()
                LanguageFolding.INSTANCE.allForLanguage(language)
                    .filterIsInstance<FoldingBuilderBase>()
                    .flatMap { builder -> builder.keyDescriptors(root, config).map { it.range } }
            }
        }

        private fun setExpanded(editor: Editor, ranges: Set<TextRange>, expanded: Boolean) {
            if (ranges.isEmpty()) return
            val caret = editor.caretModel.offset
            val folding = editor.foldingModel
            folding.runBatchFoldingOperation {
                folding.allFoldRegions
                    .filter { it.isExpanded != expanded && it.textRange in ranges }
                    .filter { expanded || caret !in it.startOffset + 1 until it.endOffset }
                    .forEach { it.isExpanded = expanded }
            }
        }
    }
}
