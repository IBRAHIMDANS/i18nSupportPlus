package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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
class I18nInlayHintsProvider : InlayHintsProvider, CompositeKeyResolver<PsiElement> {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector =
        object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val project = element.project
                val config = Settings.getInstance(project).config()
                val translationFunctionNames = Extensions.TECHNOLOGY.extensionList
                    .flatMap { it.translationFunctionNames() }

                val lang = Extensions.LANG.extensionList
                    .firstOrNull { it.canExtractKey(element, translationFunctionNames) }
                    ?: return

                // A literal expression and its leaf token are both claimed and end at the same
                // offset: the parent owns the hint, the leaf would stack a second one on it.
                if (element.firstChild == null) {
                    val parent = element.parent
                    if (parent != null && parent.firstChild === element &&
                        Extensions.LANG.extensionList.any { it.canExtractKey(parent, translationFunctionNames) }
                    ) return
                }

                val rawKey = lang.extractRawKey(element) ?: return
                val fullKey = RawKeyParser(project).parse(rawKey) ?: return

                val translation = project.service<LocalizationSourceService>()
                    .findSources(fullKey.allNamespaces(), project)
                    // The preview locale is what hints and hover show; left empty, it follows folding.
                    .filter { it.localeLabel() == config.previewLocale.ifBlank { config.foldingPreferredLanguage } }
                    .mapNotNull { resolveCompositeKey(fullKey.compositeKey, it) }
                    .filter { it.unresolved.isEmpty() }
                    .firstNotNullOfOrNull { PluralGroup.displayableValue(it.element) }
                    ?.value()?.text?.unQuote()
                    ?.renderIcu()
                    ?.ellipsis(config.foldingMaxLength)
                    ?: return

                val offset = element.textRange.endOffset
                sink.addPresentation(InlineInlayPosition(offset, true), null, null, HintFormat.default) {
                    text("↦ $translation")
                }
            }
        }
}
