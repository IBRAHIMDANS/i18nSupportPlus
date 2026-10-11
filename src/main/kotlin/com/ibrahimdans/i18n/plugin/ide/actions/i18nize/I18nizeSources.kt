package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.HardcodedVueTextInspection
import com.ibrahimdans.i18n.extensions.lang.js.VueTemplateTextExtractor
import com.ibrahimdans.i18n.plugin.ide.actions.Candidate
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionCandidates
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Where *Internationalize…* finds the texts of a file, and what writes their calls: the texts
 * *Batch Extract* offers in a JS/TS file, those of the `<template>` in a Vue component.
 */
internal object I18nizeSources {

    private val vueTemplate = VueTemplateTextExtractor()

    /** The extractors of the source languages, and the one of Vue templates, which *Batch Extract* lacks. */
    fun extractors(): List<TranslationExtractor> = Extensions.LANG.extensionList.map { it.translationExtractor() } + vueTemplate

    /** The extractor writing [candidate]'s call, or null when none takes it any more. */
    fun extractorOf(candidate: Candidate, extractors: List<TranslationExtractor> = extractors()): TranslationExtractor? =
        extractors.firstOrNull { it.canExtract(candidate.literal) }

    /** The hard-coded texts of [file], in file order. Read action. */
    fun candidates(file: PsiFile): List<Candidate> {
        if (!isVue(file)) return ExtractionCandidates.collect(file)
        return PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }
            .filter { vueTemplate.canExtract(it) }
            .map { element ->
                val text = vueTemplate.text(element)
                Candidate(literal = element, originalText = text, proposedKey = ExtractKeyModel.proposeKey(text))
            }
            .filter { it.originalText.isNotEmpty() }
    }

    /** A Vue component, whose `<template>` texts [HardcodedVueTextInspection] reports. */
    private fun isVue(file: PsiFile): Boolean = file.name.endsWith(".vue")
}
