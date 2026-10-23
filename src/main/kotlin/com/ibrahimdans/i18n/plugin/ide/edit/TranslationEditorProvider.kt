package com.ibrahimdans.i18n.plugin.ide.edit

import com.ibrahimdans.i18n.plugin.edit.TranslationEditor
import com.intellij.openapi.project.Project

/** A localization format that can edit its translation files through the portable core. */
interface TranslationEditorProvider {
    fun translationEditor(project: Project): TranslationEditor
}
