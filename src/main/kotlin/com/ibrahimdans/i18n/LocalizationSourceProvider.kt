package com.ibrahimdans.i18n

import com.intellij.psi.PsiElement

/**
 * A translation source read through the PSI, with the [Localization] that writes into it.
 */
typealias LocalizationSource = TranslationSource<PsiElement, Localization<PsiElement>>
