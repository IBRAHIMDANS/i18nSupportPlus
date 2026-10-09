package com.ibrahimdans.i18n

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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

    /**
     * Namespaces a key may name as its first segment, written with the *key* separator: Transloco's
     * scopes (`admin.title` for the key `title` of the scope `admin`). Such a segment is read as the
     * namespace only when it is one of these; any other key is parsed as before.
     */
    fun keyScopes(project: Project): Set<String> = emptySet()

    /** The scope [file] holds, for a translation file of one of [keyScopes]; null otherwise. */
    fun keyScopeOf(file: PsiFile): String? = null
}
