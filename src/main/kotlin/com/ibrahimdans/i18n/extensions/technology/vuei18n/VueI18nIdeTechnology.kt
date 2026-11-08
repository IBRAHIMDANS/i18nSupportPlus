package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.ide.settings.CodeSettingsReader
import com.ibrahimdans.i18n.plugin.ide.settings.VueI18nConfig
import com.intellij.psi.PsiFile

/**
 * The core's [VueI18nTechnology], plus what the IDE reads in a project's Nuxt or `createI18n`
 * configuration for the setup wizard ([VueI18nConfigReader]): the part that needs the JavaScript PSI,
 * kept out of the core and found by the wizard through [CodeSettingsReader].
 */
class VueI18nIdeTechnology : VueI18nTechnology(), CodeSettingsReader {

    override fun wizardVueConfig(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): VueI18nConfig? =
        VueI18nConfigReader.read(file, relativePath, isDirectory)
}
