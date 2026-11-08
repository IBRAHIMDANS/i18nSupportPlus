package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.ide.settings.CodeSettingsReader
import com.ibrahimdans.i18n.plugin.ide.settings.VueI18nConfig
import com.intellij.psi.PsiFile

/**
 * What the JavaScript plugin reads for vue-i18n: a project's Nuxt or `createI18n` configuration, for
 * the setup wizard ([VueI18nConfigReader]). Registered in `jsConfig.xml`, found through
 * [CodeSettingsReader]; the technology itself is the core's [VueI18nTechnology].
 */
class VueI18nJsReader : CodeSettingsReader {

    override fun wizardVueConfig(file: PsiFile, relativePath: String, isDirectory: (String) -> Boolean): VueI18nConfig? =
        VueI18nConfigReader.read(file, relativePath, isDirectory)
}
