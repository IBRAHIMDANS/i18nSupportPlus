package com.ibrahimdans.i18n

import com.intellij.psi.PsiElement

/**
 * A technology whose sources are read through the PSI, with the [Localization] that writes into them.
 */
typealias Technology = TranslationTechnology<PsiElement, Localization<PsiElement>>
