package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.ibrahimdans.i18n.plugin.utils.InlangMessageRenderer
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil

/**
 * Displays the resolved i18n translation inline after each key expression.
 * Toggled via Editor > Inlay Hints > "i18n translations" (native IntelliJ setting).
 * Independent from the folding mechanism.
 *
 * A document cache of the offsets already given a hint, invalidated only on edit, used to guard
 * against duplicates: every pass replayed without an edit found each offset already taken and
 * showed no hint at all. The duplicate it was actually hiding within a pass — a literal expression
 * and its leaf token both claimed — is filtered below without any state.
 */
open class I18nInlayHintsProvider : InlayHintsProvider, CompositeKeyResolver<PsiElement> {

    /**
     * Whether the keys sit in the language fragments injected into the file rather than in its own
     * PSI: a Blade view's `@lang('…')` and `{{ __('…') }}` are PHP injected into it, and the pass
     * visits the view's elements only.
     */
    protected open fun readsInjectedFragments(): Boolean = false

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector {
        // One collector per file and pass, asked of every PSI element: what does not depend on the
        // element is read here once. Per element, it allocated a Config and rebuilt the name list
        // for every element of the file — the whole of the plugin's time while typing, in a JFR
        // profile of `HighlightingPerformanceTest`.
        val config = Settings.getInstance(file.project).config()
        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        val langs = Extensions.LANG.extensionList
        val injections = InjectedLanguageManager.getInstance(file.project)
        // One fragment may span several hosts — Blade injects all of a view's PHP as one file —
        // and is read once per pass, not once per host holding a part of it.
        val visitedFragments = mutableSetOf<PsiFile>()
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (!readsInjectedFragments()) return collect(element, sink) { it }
                if (element !is PsiLanguageInjectionHost) return
                injections.getInjectedPsiFiles(element)?.forEach { injected ->
                    val fragment = injected.first as? PsiFile ?: return@forEach
                    if (!visitedFragments.add(fragment)) return@forEach
                    PsiTreeUtil.processElements(fragment) { collect(it, sink) { offset -> injections.injectedToHost(it, offset) }; true }
                }
            }

            /** Adds the hint of [element], placed at the offset [toHost] gives for its end. */
            private fun collect(element: PsiElement, sink: InlayTreeSink, toHost: (Int) -> Int) {
                val project = element.project
                val lang = langs.firstOrNull { it.canExtractKey(element, translationFunctionNames) } ?: return

                // A literal expression and its leaf token are both claimed and end at the same
                // offset: the parent owns the hint, the leaf would stack a second one on it.
                if (element.firstChild == null) {
                    val parent = element.parent
                    if (parent != null && parent.firstChild === element &&
                        langs.any { it.canExtractKey(parent, translationFunctionNames) }
                    ) return
                }

                val rawKey = lang.extractRawKey(element) ?: return
                val fullKey = RawKeyParser(project).parse(rawKey, element) ?: return

                val sources = project.service<LocalizationSourceService>().findReadSources(fullKey, element)
                // The preview locale is what hints and hover show; left empty, it follows folding.
                // Matched through LocaleMatching, so `en` finds `en-GB` when there is no plain `en`.
                val wanted = config.previewLocale.ifBlank { config.foldingPreferredLanguage }
                val locale = LocaleMatching.pick(wanted, sources.map { it.localeLabel() })
                val translation = sources
                    .filter { it.localeLabel() == locale }
                    // With the plural separator, as the annotator and the gutter resolve: a key held as
                    // `item_one` / `item_other` got no hint, its first form is shown.
                    .flatMap { resolve(fullKey.compositeKey, it, config.pluralSeparator) }
                    .filter { it.unresolved.isEmpty() }
                    .firstNotNullOfOrNull { PluralGroup.displayableValue(it.element) }
                    // A Paraglide message with variants is a JSON array: shown as its catch-all branch.
                    ?.value()?.text?.let(InlangMessageRenderer::preview)?.unQuote()
                    ?.renderIcu()
                    ?.ellipsis(config.foldingMaxLength)
                    ?: return

                val offset = toHost(element.textRange.endOffset)
                sink.addPresentation(InlineInlayPosition(offset, true), null, null, HintFormat.default) {
                    text("↦ $translation")
                }
            }
        }
    }
}
