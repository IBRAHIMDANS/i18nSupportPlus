package com.ibrahimdans.i18n

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

interface Technology {
    fun translationFunctionNames(): List<String>
    fun findSourcesByConfiguration(project: Project): List<LocalizationSource>
    fun initialize(project: Project)
    fun cfgNamespaces(): List<String>

    /**
     * The framework id this technology implements, as [com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector]
     * names it (`i18next`, `vue-i18n`): what a module's preset selects. Null for a technology no preset names.
     */
    fun frameworkId(): String? = null
}
