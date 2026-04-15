package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Displays the resolved i18n translation inline after each key expression.
 * Toggled via Editor > Inlay Hints > "i18n translations" (native IntelliJ setting).
 * Independent from the folding mechanism.
 *
 * This provider is registered for multiple languages (JS, JSX, TS, TSX). IntelliJ invokes
 * createCollector once per matching language registration for the same file, which would
 * produce duplicate hints. A document-level cache (keyed by modificationStamp) deduplicates
 * by tracking text offsets that have already received a hint in the current pass.
 */
class I18nInlayHintsProvider : InlayHintsProvider, CompositeKeyResolver<PsiElement> {

    companion object {
        private val HINTS_CACHE_KEY = Key.create<Pair<Long, MutableSet<Int>>>("i18n.inlay.offsets")
    }

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

                val rawKey = lang.extractRawKey(element) ?: return
                val fullKey = RawKeyParser(project).parse(rawKey) ?: return

                val translation = project.service<LocalizationSourceService>()
                    .findSources(fullKey.allNamespaces(), project)
                    .filter { it.parent == config.foldingPreferredLanguage }
                    .map { resolveCompositeKey(fullKey.compositeKey, it) }
                    .firstOrNull { it.unresolved.isEmpty() && it.element?.isLeaf() == true }
                    ?.element?.value()?.text?.unQuote()
                    ?.ellipsis(config.foldingMaxLength)
                    ?: return

                // Deduplicate across multiple language-registration collector calls for the same file.
                // IntelliJ invokes createCollector once per registered language (JS/JSX/TS/TSX),
                // so without this guard the same hint would appear N times at the same offset.
                val offset = element.textRange.endOffset
                val doc = editor.document
                val processedOffsets = synchronized(doc) {
                    val modStamp = doc.modificationStamp
                    val cached = doc.getUserData(HINTS_CACHE_KEY)
                    if (cached != null && cached.first == modStamp) {
                        cached.second
                    } else {
                        val newSet: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap())
                        doc.putUserData(HINTS_CACHE_KEY, Pair(modStamp, newSet))
                        newSet
                    }
                }
                if (!processedOffsets.add(offset)) return

                sink.addPresentation(InlineInlayPosition(offset, true), null, null, HintFormat.default) {
                    text("↦ $translation")
                }
            }
        }
}
