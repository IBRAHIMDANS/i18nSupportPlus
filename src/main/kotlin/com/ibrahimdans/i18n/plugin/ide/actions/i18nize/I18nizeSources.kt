package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.HardcodedVueTextInspection
import com.ibrahimdans.i18n.extensions.lang.js.VueScriptTextExtractor
import com.ibrahimdans.i18n.extensions.lang.js.VueTemplateTextExtractor
import com.ibrahimdans.i18n.plugin.ide.actions.Candidate
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractKeyModel
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionCandidates
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Where *Internationalize…* finds the texts of a file, and what writes their calls: the texts
 * *Batch Extract* offers in a JS/TS file, those of the `<template>` and the `<script>` of a Vue component.
 */
internal object I18nizeSources {

    private val vueTemplate = VueTemplateTextExtractor()
    private val vueScript = VueScriptTextExtractor()

    /** The extractors of the source languages, and those of Vue components, which *Batch Extract* lacks. */
    fun extractors(): List<TranslationExtractor> =
        Extensions.LANG.extensionList.map { it.translationExtractor() } + vueTemplate + vueScript

    /** The hard-coded texts of [file], in file order. Read action. */
    fun candidates(file: PsiFile): List<Candidate> {
        if (!isVue(file)) return ExtractionCandidates.collect(file)
        val template = PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }
            .filter { vueTemplate.canExtract(it) }
            .map { it to vueTemplate }
        val script = PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
            .mapNotNull { it.firstChild }
            .filter { vueScript.canExtract(it) && !vueScript.isExtracted(it) }
            // The literals of one concatenation (`'Hello ' + name + '!'`) are one message: replaced once.
            .distinctBy { vueScript.textRange(it) }
            .map { it to vueScript }
        return (template + script)
            .sortedBy { (element, _) -> element.textRange.startOffset }
            .map { (element, extractor) -> candidate(element, extractor.text(element).trim()) }
            .filter { it.originalText.isNotEmpty() }
    }

    private fun candidate(element: PsiElement, text: String) =
        Candidate(literal = element, originalText = text, proposedKey = ExtractKeyModel.proposeKey(text))

    /** A Vue component: the texts of its `<template>` are those [HardcodedVueTextInspection] reports. */
    private fun isVue(file: PsiFile): Boolean = file.name.endsWith(".vue")
}
