package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionAction
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * *Extract i18n key* on a text a hard-coded text inspection reports: delegates to
 * [ExtractI18nIntentionAction], which asks for the key and writes it. [extractor] is the one the
 * text needs when no source language extracts it — an Angular template's markup —, null otherwise.
 */
internal class ExtractTextQuickFix(leaf: PsiElement, private val extractor: TranslationExtractor? = null) :
    LocalQuickFixAndIntentionActionOnPsiElement(leaf) {

    override fun getText(): String = PluginBundle.message("action.intention.extract.key")
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, psiFile: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        ExtractI18nIntentionAction(extractor).invoke(project, editor ?: return, startElement)
    }
}
