package com.ibrahimdans.i18n

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

class Extensions {
    companion object {
        val LOCALIZATION = ExtensionPointName.create<Localization<PsiElement>>("com.ibrahimdans.i18n.localization")
        val LANG = ExtensionPointName.create<Lang>("com.ibrahimdans.i18n.lang")
        val TECHNOLOGY = ExtensionPointName.create<Technology>("com.ibrahimdans.i18n.technology")
    }
}
