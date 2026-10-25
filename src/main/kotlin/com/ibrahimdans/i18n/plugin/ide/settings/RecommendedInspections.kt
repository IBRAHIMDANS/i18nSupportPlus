package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager

/**
 * Inspections off by default that the setup wizard offers to turn on: nothing in the IDE
 * said they exist, and the wizard is the one moment the configuration is looked at.
 *
 * Only ever written to the project's own profile: a project set to an IDE profile is left alone,
 * since turning a check on there would turn it on in every project sharing that profile.
 */
object RecommendedInspections {

    const val UNUSED_KEY = "I18nUnusedKey"

    /** The Vue one is registered only with the Vue plugin; [enable] skips it when absent. */
    val HARDCODED_TEXT = listOf("I18nHardcodedJsxText", "I18nHardcodedVueText")

    /** Whether [enable] can write anything: the project uses a profile of its own. */
    fun canEnable(project: Project): Boolean =
        InspectionProjectProfileManager.getInstance(project).currentProfile.isProjectLevel

    /** Turns [shortNames] on in the project's profile; never turns anything off. */
    fun enable(project: Project, shortNames: Collection<String>) {
        if (shortNames.isEmpty() || !canEnable(project)) return
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val known = shortNames.filter { profile.getToolsOrNull(it, project) != null }
        if (known.isEmpty()) return
        profile.modifyProfile { model -> known.forEach { model.setToolEnabled(it, true) } }
    }
}
