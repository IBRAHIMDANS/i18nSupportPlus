package com.ibrahimdans.i18n

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

class Extensions {
    companion object {
        val LOCALIZATION = ExtensionPointName.create<Localization<PsiElement>>("com.ibrahimdans.i18n.localization")
        val LANG = ExtensionPointName.create<Lang>("com.ibrahimdans.i18n.lang")
        val TECHNOLOGY = ExtensionPointName.create<Technology>("com.ibrahimdans.i18n.technology")
        val COMPONENT_SOURCES = ExtensionPointName.create<ComponentSourceProvider>("com.ibrahimdans.i18n.componentSources")
    }
}

/** The [I18nRegistry] of the IDE: the formats and technologies registered on the plugin's extension points. */
object IdeI18nRegistry : I18nRegistry<PsiElement, Localization<PsiElement>> {
    override fun formats(): List<Localization<PsiElement>> = Extensions.LOCALIZATION.extensionList
    override fun technologies(): List<Technology> = Extensions.TECHNOLOGY.extensionList
}
