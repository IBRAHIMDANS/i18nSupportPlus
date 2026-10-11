package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceContributor
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceProvider
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReference
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * From a key of a PHP translation file to the code using it.
 *
 * Every array key of every PHP file matches the pattern: the cheap test — a locale directory —
 * goes first, the source scan only after it, so a key of a configuration array costs nothing.
 */
class PhpArrayReferenceAssistant : TranslationReferenceAssistant<StringLiteralExpression> {

    private val provider = TranslationToCodeReferenceProvider()

    override fun pattern(): ElementPattern<out StringLiteralExpression> =
        PlatformPatterns.psiElement(StringLiteralExpression::class.java)
            .withParent(PlatformPatterns.psiElement(PhpElementTypes.ARRAY_KEY))

    override fun references(element: StringLiteralExpression): List<PsiReference> {
        val file = element.containingFile ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val namespace = PhpArrayLocalization.namespaceOf(virtualFile) ?: return emptyList()
        val path = PhpArrays.pathOf(element) ?: return emptyList()
        if (TranslationFileScope.sourceOf(file)?.localization !is PhpArrayLocalization) return emptyList()
        return provider.getReferences(element, element.valueRange, listOf(namespace) + path)
    }
}

/** Registers [PhpArrayReferenceAssistant]. */
class PhpArrayReferenceContributor : TranslationToCodeReferenceContributor<StringLiteralExpression>(PhpArrayReferenceAssistant())
