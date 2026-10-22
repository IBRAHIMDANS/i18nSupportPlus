package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Gets project's search scope
 *
 * An extension rather than a member, so that [Config] itself stays free of the IntelliJ Platform.
 */
fun Config.searchScope(project: Project): GlobalSearchScope =
    if (this.searchInProjectOnly) GlobalSearchScope.projectScope(project)
    else GlobalSearchScope.allScope(project)
