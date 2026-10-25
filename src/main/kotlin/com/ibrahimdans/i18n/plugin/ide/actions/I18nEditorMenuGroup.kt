package com.ibrahimdans.i18n.plugin.ide.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * The *i18n* submenu of the editor's context menu. Each entry decides where it applies; the
 * submenu itself is hidden when none does, rather than shown greyed out in a Java or Markdown file.
 */
class I18nEditorMenuGroup : DefaultActionGroup() {

    init {
        templatePresentation.isHideGroupIfEmpty = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
