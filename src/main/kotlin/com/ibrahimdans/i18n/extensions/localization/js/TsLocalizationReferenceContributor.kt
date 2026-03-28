package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReferenceContributor
import com.intellij.psi.PsiElement

class TsLocalizationReferenceContributor: TranslationToCodeReferenceContributor<PsiElement>(
    TsReferenceAssistant()
)
