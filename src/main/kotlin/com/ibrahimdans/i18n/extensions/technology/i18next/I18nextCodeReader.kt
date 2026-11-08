package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.CodeReader
import com.ibrahimdans.i18n.LocalizationSource
import com.intellij.openapi.project.Project

/**
 * What [I18NextTechnology] reads in a project's JavaScript: the initialisation files and their
 * `resources`, `fallbackNS` and the configuration files. Given by the JavaScript plugin's
 * [I18nextJsReader] through [com.ibrahimdans.i18n.Extensions.CODE_READER]; without it, the
 * technology knows its call names and nothing of the code.
 */
interface I18nextCodeReader : CodeReader {

    /** Starts the search of the configuration files in [project]'s code, when the indexes allow it. */
    fun initialize(project: Project)

    /** The configuration files [initialize] found, by name. */
    fun cfgNamespaces(): List<String>

    /** The translation sources an initialisation file holds inline (`resources: { en: { translation: {…} } }`). */
    fun initSources(project: Project): List<LocalizationSource>

    /** The `fallbackNS` of the configuration files, in order. */
    fun fallbackNamespaces(project: Project): List<String>
}
