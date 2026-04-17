package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.*
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

// Translation→code navigation requires PSI types from org.jetbrains.plugins.localization,
// which has no build for IntelliJ 243.x+. References stay disabled until the plugin is available.
class PlainObjectReferenceAssistant : TranslationReferenceAssistant<PsiElement> {
    override fun pattern(): ElementPattern<out PsiElement> = PlatformPatterns.alwaysFalse()

    override fun references(element: PsiElement): List<PsiReference> = emptyList()
}
