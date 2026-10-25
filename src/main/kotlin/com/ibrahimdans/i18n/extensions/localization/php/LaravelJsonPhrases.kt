package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.extensions.lang.php.LaravelProject
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Laravel's JSON translation files, `lang/<locale>.json`, whose keys are the phrases themselves:
 * `__('Saved.')`, `__('Hello, :name')`. Laravel looks a call up there first, the text whole, and
 * reads it as `file.key` only when no JSON file holds it — so a phrase holding `.` or `:` must not
 * be cut at the plugin's separators.
 */
internal object LaravelJsonPhrases {

    private const val LANG_FOLDER = "lang"

    /** Whether [text] is a key of a JSON translation file directly under a `lang` folder of a Laravel project. */
    fun contains(project: Project, text: String): Boolean {
        if (!LaravelProject.isLaravel(project)) return false
        return project.service<LocalizationSourceService>().findAllSources(project).any { source ->
            val file = source.tree?.value()?.containingFile?.virtualFile ?: return@any false
            file.extension == "json" && file.parent?.name == LANG_FOLDER && source.tree?.findChild(text) != null
        }
    }
}
