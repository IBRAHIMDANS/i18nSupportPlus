package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyRangesCalculator
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.isQuoted
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement

/**
 * Annotator for i18n keys
 */
abstract class CompositeKeyAnnotatorBase(private val lang: Lang): Annotator, CompositeKeyResolver<PsiElement> {

    /**
     * Tries to parse element as i18n key and annotates it when succeeded
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val config = Settings.getInstance(element.project).config()
        val excludedDirs = config.excludedDirectorySet()
        if (excludedDirs.isNotEmpty()) {
            val filePath = element.containingFile?.virtualFile?.path ?: return
            if (filePath.split('/').any { it in excludedDirs }) return
        }
        if(lang.canExtractKey(element, Extensions.TECHNOLOGY.extensionList.flatMap {it.translationFunctionNames()})) {
            lang.extractRawKey(element)?.let { rawKey ->
                RawKeyParser(element.project).parse(rawKey)
            }?.also {
                annotateI18nLiteral(it, element, holder, config)
            }
        }
    }

    private fun annotateI18nLiteral(fullKey: FullKey, element: PsiElement, holder: AnnotationHolder, config: Config) {
        val annotationHelper = AnnotationHelper(
            holder,
            KeyRangesCalculator(element.textRange.shiftRight(element.text.unQuote().indexOf(fullKey.source)), element.text.isQuoted()),
        )
        val sourceService = element.project.service<LocalizationSourceService>()
        val files = sourceService.findSources(fullKey.allNamespaces(), element.project)
        if (files.isEmpty()) {
            if (fullKey.ns == null) {
                annotationHelper.unresolvedDefaultNs(fullKey)
            } else {
                annotationHelper.unresolvedNs(fullKey, fullKey.ns)
            }
        }
        else {
            if (fullKey.ns != null) {
                val nsFiles = sourceService.findNamespaceFiles(fullKey.allNamespaces(), element.project)
                if (nsFiles.isEmpty()) {
                    annotationHelper.unresolvedNs(fullKey, fullKey.ns)
                    return
                }
            }
            val pluralSeparator = config.pluralSeparator
            val references = files.flatMap {resolve(fullKey.compositeKey, it, pluralSeparator)}
            val allEqual = references.zipWithNext().all { it.first.path == it.second.path }
            val mostResolvedReference = if (allEqual) references.first() else references.maxByOrNull { v -> v.path.size }!!
            if (mostResolvedReference.unresolved.isEmpty()) {
                if (!allEqual && config.partialTranslationInspectionEnabled) {
                    annotationHelper.annotatePartiallyTranslated(fullKey, references)
                } else {
                    if (mostResolvedReference.element?.isLeaf() ?: false) {
                        annotationHelper.annotateResolved(fullKey)
                    } else {
                        annotationHelper.annotateReferenceToObject(fullKey)
                    }
                }
            } else {
                // Skip annotation when only unresolved elements are dynamic template expressions (e.g. ${arg})
                // — they can't be statically resolved, so no false "Unresolved key" warning.
                val onlyTemplateUnresolved = mostResolvedReference.unresolved.all {
                    it.text.startsWith("\${") && it.text.endsWith("}")
                }
                if (!onlyTemplateUnresolved) {
                    annotationHelper.unresolvedKey(fullKey, mostResolvedReference)
                }
            }
        }
    }
}

