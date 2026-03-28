package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.*
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

// Plain Object support is not yet implemented — all methods are no-ops to prevent runtime crashes
class PlainObjectReferenceAssistant : TranslationReferenceAssistant<PsiElement> {
    override fun pattern(): ElementPattern<out PsiElement> = PlatformPatterns.alwaysFalse()

    override fun references(element: PsiElement): List<PsiReference> = emptyList()
}
