package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StartupAction : ProjectActivity {
    override suspend fun execute(project: Project) {
        val workspace = IdeWorkspace(project)
        Extensions.TECHNOLOGY.extensionList.forEach {
            it.initialize(workspace)
        }
    }
}
