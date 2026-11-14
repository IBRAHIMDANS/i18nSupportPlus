package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.SimilarKeys
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.isQuoted
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * *Use 'button.save'* on a key no translation file holds, when one it does hold is a typo away:
 * the key written in the code is replaced by it, its namespace kept.
 *
 * The keys are looked up when the fixes are shown — under Alt+Enter, on the key — not while the
 * file is highlighted: the inspection reports the unresolved keys on every pass, and reading a
 * catalogue for each would cost every pass, for a list nobody opened. [forKey] gives the
 * [SimilarKeys.LIMIT] fixes, sharing one lookup; a fix with no key to offer is not available.
 *
 * *Use namespace 'common'* is the same fix on a namespace no file holds (`t('comon:save')`):
 * [forNamespace] offers the namespaces of the caller's module a typo away, the key kept.
 */
class UseSimilarKeyQuickFix private constructor(
    private val element: PsiElement,
    private val keyRange: TextRange,
    private val suggestions: Lazy<List<Suggestion>>,
    private val index: Int,
    private val textKey: String = "quickfix.use.similar.key",
) : IntentionAction, HighPriorityAction {

    /** A key to use: [key] as the file names it, [source] the text written in the code for it. */
    class Suggestion(val key: String, val source: String)

    override fun getText(): String = suggestions.value.getOrNull(index)
        ?.let { PluginBundle.message(textKey, it.key) }
        ?: familyName

    override fun getFamilyName(): String = PluginBundle.message("quickfix.use.similar.key.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        element.isValid && index < suggestions.value.size

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (!element.isValid) return
        val suggestion = suggestions.value.getOrNull(index) ?: return
        val injections = InjectedLanguageManager.getInstance(project)
        val host = injections.getTopLevelFile(element) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(host) ?: return
        // A key in a Vue template or a Blade view is in an injected fragment: written in its host.
        val range = injections.injectedToHost(element, keyRange)
        if (document.getText(range) != keyText()) return
        document.replaceString(range.startOffset, range.endOffset, suggestion.source)
        documents.commitDocument(document)
    }

    private fun keyText(): String = element.containingFile.text.substring(keyRange.startOffset, keyRange.endOffset)

    companion object {

        /**
         * The fixes offering, for [fullKey] written in [element], the keys of [sources] closest to
         * it; none for a key that names no key to replace — a runtime segment, a source text, an
         * i18next `context` — or that its element does not write as it reads it.
         */
        fun forKey(element: PsiElement, fullKey: FullKey, sources: List<LocalizationSource>, config: Config): List<IntentionAction> {
            if (fullKey.isDynamic || fullKey.sourceText || fullKey.context != null || sources.isEmpty()) return emptyList()
            val keyRange = keyRangeOf(element, fullKey) ?: return emptyList()
            val suggestions = lazy { suggest(fullKey, sources, config) }
            return (0 until SimilarKeys.LIMIT).map { UseSimilarKeyQuickFix(element, keyRange, suggestions, it) }
        }

        /**
         * The fixes offering, for the namespace [ns] that [fullKey] writes before its key in
         * [element] and no file holds, the namespaces of the caller's module closest to it; none
         * when the key does not start with it — a namespace from a hook or an option is not
         * written there to replace.
         */
        fun forNamespace(element: PsiElement, fullKey: FullKey, ns: Literal, config: Config): List<IntentionAction> {
            if (fullKey.sourceText || !fullKey.source.startsWith(ns.text + config.nsSeparator)) return emptyList()
            val keyRange = keyRangeOf(element, fullKey) ?: return emptyList()
            val suggestions = lazy { suggestNamespaces(element, fullKey, ns.text, config) }
            return (0 until SimilarKeys.LIMIT).map {
                UseSimilarKeyQuickFix(element, keyRange, suggestions, it, "quickfix.use.similar.namespace")
            }
        }

        /** Where [fullKey], as written, lies in [element]'s file, or null when [element] does not write it. */
        private fun keyRangeOf(element: PsiElement, fullKey: FullKey): TextRange? {
            val offset = element.text.unQuote().indexOf(fullKey.source)
            if (offset < 0) return null
            val start = element.textRange.startOffset + offset + if (element.text.isQuoted()) 1 else 0
            return TextRange(start, start + fullKey.source.length)
        }

        private fun suggest(fullKey: FullKey, sources: List<LocalizationSource>, config: Config): List<Suggestion> {
            val unknown = fullKey.compositeKey.map { it.text }
            val keys = sources.asSequence()
                .mapNotNull { it.tree?.takeIf { tree -> tree.value().isValid } }
                .flatMap { tree -> SimilarKeys.keysOf(tree, { it.value().text.unQuote() }, config.pluralSeparator) }
                .distinct()
                .take(SimilarKeys.MAX_KEYS)
                .toList()
            return SimilarKeys.closest(unknown, keys).mapNotNull { candidate ->
                SimilarKeys.rewrite(fullKey.source, unknown, fullKey.keyPrefix.map { it.text }, candidate, config.keySeparator)
                    ?.let { Suggestion(candidate.joinToString(config.keySeparator), it) }
            }
        }

        private fun suggestNamespaces(element: PsiElement, fullKey: FullKey, ns: String, config: Config): List<Suggestion> {
            val sourceService = element.project.service<LocalizationSourceService>()
            val defaultNamespace = config.defaultNamespaces().first()
            val namespaces = sourceService.findAllSources(element.project)
                .map { TranslationDataLoader.extractNamespace(it, defaultNamespace) }
                .distinct()
            // Every namespace of the project is a candidate; one the caller's module does not read
            // — another application's — is dropped: the key would stay unresolved there.
            return SimilarKeys.closestNamespaces(ns, namespaces, limit = namespaces.size).asSequence()
                .filter { sourceService.findSources(listOf(it), element).isNotEmpty() }
                .take(SimilarKeys.LIMIT)
                .mapNotNull { candidate ->
                    SimilarKeys.rewriteNamespace(fullKey.source, ns, candidate, config.nsSeparator)?.let { Suggestion(candidate, it) }
                }
                .toList()
        }
    }
}
