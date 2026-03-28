package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that opens the SetupWizardDialog when no i18n configuration
 * is detected in the project (no modules configured, default namespace still at default value).
 *
 * Registered in plugin.xml as a <postStartupActivity>.
 */
class SetupWizardStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!needsSetup(project)) return

        // Must run on the EDT since we are opening a dialog
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            SetupWizardDialog(project).show()
        }
    }

    /**
     * Returns true when the project has no i18n modules configured
     * AND the defaultNs is still the factory default ("translation").
     * This avoids showing the wizard to users who already have a config.
     */
    private fun needsSetup(project: Project): Boolean {
        val settings = Settings.getInstance(project)
        val defaultConfig = Config()
        return settings.modules.isEmpty()
            && settings.defaultNs == defaultConfig.defaultNs
            && settings.translationsRoot.isBlank()
    }
}
