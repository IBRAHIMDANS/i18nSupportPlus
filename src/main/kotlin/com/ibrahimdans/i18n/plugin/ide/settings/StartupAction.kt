package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.Extensions
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StartupAction : ProjectActivity {
    override suspend fun execute(project: Project) {
        Extensions.TECHNOLOGY.extensionList.forEach {
            it.initialize(project)
        }
    }
}
