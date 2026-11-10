package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.extractors.OptionsExtractor
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.utils.KeyRangesCalculator
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.RangesCalculator
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.ibrahimdans.i18n.plugin.utils.isQuoted
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement

/**
 * Reads [element] as a key of [lang] and says, to a [KeyReport], whether it resolves: the one
 * check behind the annotator's colouring and the unresolved key inspection.
 */
class KeyCheck(private val lang: Lang) : CompositeKeyResolver<PsiElement> {

    /**
     * Tries to parse element as i18n key and reports it when succeeded
     */
    fun check(element: PsiElement, report: KeyReport) {
        // Asked of every element of every file: the one test most of them fail comes first, and the
        // configuration and the exclusions — a new Config, a path split — only for a key.
        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        if (!lang.canExtractKey(element, translationFunctionNames)) return
        if (isLeafOfClaimedParent(element, translationFunctionNames)) return
        val config = Settings.getInstance(element.project).config()
        val virtualFile = element.containingFile?.virtualFile ?: return
        val excludedDirs = config.excludedDirectorySet()
        if (excludedDirs.isNotEmpty() && virtualFile.path.split('/').any { it in excludedDirs }) return
        val excludedExts = config.excludedFileExtensionSet()
        if (excludedExts.isNotEmpty() && virtualFile.extension?.lowercase() in excludedExts) return
        lang.extractRawKey(element)?.let { rawKey ->
            RawKeyParser(element.project).parse(rawKey, element)
        }?.also {
            checkI18nLiteral(it, element, report, config)
        }
    }

    /**
     * A literal expression and its leaf token are both claimed, and both annotate the same range:
     * every unresolved key was reported twice. The parent owns the annotation — the same stateless
     * rule the gutter and inlay hints apply.
     */
    private fun isLeafOfClaimedParent(element: PsiElement, translationFunctionNames: List<String>): Boolean {
        if (element.firstChild != null) return false
        val parent = element.parent ?: return false
        return parent.firstChild === element && lang.canExtractKey(parent, translationFunctionNames)
    }

    private fun checkI18nLiteral(fullKey: FullKey, element: PsiElement, report: KeyReport, config: Config) {
        val offset = element.text.unQuote().indexOf(fullKey.source)
        // A key rebuilt from its element rather than written in it (a Lingui `<Trans>` msgid with
        // `{0}` or `<0>…</0>`) has no position of its own: the whole element carries the annotation.
        val ranges = if (offset < 0) WholeElementRanges(element.textRange.toTextSpan())
            else KeyRangesCalculator(element.textRange.shiftRight(offset).toTextSpan(), element.text.isQuoted())
        val annotationHelper = AnnotationHelper(report, ranges, element.containingFile)
        val sourceService = element.project.service<LocalizationSourceService>()
        val files = sourceService.findReadSources(fullKey, element)
        val ns = fullKey.ns
        if (files.isEmpty()) {
            if (anotherKeyResolves(element, config)) return
            if (ns == null) {
                annotationHelper.unresolvedDefaultNs(fullKey)
            } else {
                annotationHelper.unresolvedNs(fullKey, ns)
            }
        }
        else {
            if (ns != null) {
                val nsFiles = sourceService.findNamespaceFiles(fullKey.allNamespaces(), element.project)
                if (nsFiles.isEmpty()) {
                    if (!anotherKeyResolves(element, config)) annotationHelper.unresolvedNs(fullKey, ns)
                    return
                }
            }
            val pluralSeparator = config.pluralSeparator
            val references = files.flatMap {resolve(fullKey, it, pluralSeparator)}
            val allEqual = references.zipWithNext().all { it.first.path == it.second.path }
            val mostResolvedReference = if (allEqual)
                references.firstOrNull() ?: return
            else
                references.maxByOrNull { v -> v.path.size } ?: return
            if (mostResolvedReference.unresolved.isEmpty()) {
                // A locale not checked for missing keys never makes a key partially translated,
                // and its quick fix creates the key in the checked locales only. Nor does a regional
                // locale lacking the key its language resolves (`en-GB` beside `en`): it inherits it.
                val deepest = mostResolvedReference.path.size
                val resolvedLocales = references.filter { it.path.size == deepest }.mapTo(HashSet()) { it.localizationSource.localeLabel() }
                val checked = references.filterNot {
                    val locale = it.localizationSource.localeLabel()
                    config.ignoresLocale(locale) ||
                        (it.path.size < deepest && config.parentLocale(locale)?.let(resolvedLocales::contains) == true)
                }
                val partial = checked.zipWithNext().any { it.first.path != it.second.path }
                if (partial && config.partialTranslationInspectionEnabled) {
                    annotationHelper.annotatePartiallyTranslated(fullKey, checked)
                } else {
                    if (mostResolvedReference.element?.isLeaf() ?: false) {
                        annotationHelper.annotateResolved(fullKey)
                    } else if (PluralGroup.isPluralGroup(mostResolvedReference.element) || lang.allowsObjectValue(element) ||
                        OptionsExtractor.returnsObjects(element)) {
                        // i18n-js and friends pluralize into a nested object rather than through
                        // the flat `key_one` suffixes the resolver expands: the key legitimately
                        // points at that object, so it is resolved, not a reference to an object —
                        // as is a call reading the object itself (next-intl's `t.raw`, i18next's
                        // `returnObjects: true`).
                        annotationHelper.annotateResolved(fullKey)
                    } else if (mostResolvedReference.path.lastOrNull()?.text != "*") {
                        // A terminal wildcard (e.g. a dynamic `${expr}` segment) legitimately
                        // points at the parent object and is resolved at runtime, so it must
                        // not be reported as a static "Reference to object".
                        annotationHelper.annotateReferenceToObject(fullKey)
                    }
                }
            } else {
                // Skip annotation when only unresolved elements are dynamic template expressions (e.g. ${arg})
                // — they can't be statically resolved, so no false "Unresolved key" warning.
                val onlyTemplateUnresolved = mostResolvedReference.unresolved.all {
                    it.text.startsWith("\${") && it.text.endsWith("}")
                }
                // A dynamic `${expr}` segment in the middle of the key tokenizes to a wildcard.
                // Once resolution has passed through it, the remaining static segments depend on
                // the runtime value and cannot be verified statically — so don't report them.
                val resolvedViaDynamicWildcard = mostResolvedReference.path.any { it.text == "*" }
                if (!onlyTemplateUnresolved && !resolvedViaDynamicWildcard && !anotherKeyResolves(element, config)) {
                    // i18next shows a default value while the key is missing: not a broken screen,
                    // a key still to create — with that text.
                    annotationHelper.unresolvedKey(fullKey, mostResolvedReference, OptionsExtractor.defaultValue(element))
                }
            }
        }
    }

    /**
     * `t(['specific', 'generic'])`: i18next shows the first key that exists, so a missing one is
     * not reported while another key of the array resolves in full.
     */
    private fun anotherKeyResolves(element: PsiElement, config: Config): Boolean {
        val fallbacks = OptionsExtractor.fallbackKeys(element)
        if (fallbacks.isEmpty()) return false
        val sourceService = element.project.service<LocalizationSourceService>()
        return fallbacks.any { fallback ->
            val fullKey = lang.extractRawKey(fallback)?.let { RawKeyParser(element.project).parse(it, fallback) } ?: return@any false
            sourceService.findReadSources(fullKey, fallback)
                .flatMap { resolve(fullKey, it, config.pluralSeparator) }
                .any { it.unresolved.isEmpty() }
        }
    }
}

/** Every range of a key that is not written as such in its element: the element itself. */
private class WholeElementRanges(private val span: TextSpan) : RangesCalculator {
    override fun compositeKeyFullBounds(fullKey: FullKey): TextSpan = span
    override fun unresolvedKey(fullKey: FullKey, resolvedPath: List<Literal>): TextSpan = span
    override fun unresolvedNs(fullKey: FullKey): TextSpan = span
}
