package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class RecommendedInspectionsTest : PlatformBaseTest() {

    private val manager get() = ProjectInspectionProfileManager.getInstance(project)

    private val checks = listOf(RecommendedInspections.UNUSED_KEY) + RecommendedInspections.HARDCODED_TEXT

    /**
     * Runs [block] on a profile of the project's own, as *Project Default* is, holding every
     * registered inspection with the checks off. The fixture swaps in a profile managed at the
     * application level, which cannot stand for a project's; the light project is shared, so the
     * fixture's profile is put back and the one made here removed. Tests leave a profile's tools
     * uninitialized unless [InspectionProfileImpl.INIT_INSPECTIONS] says otherwise.
     */
    private fun onProjectProfile(block: (InspectionProfileImpl) -> Unit) {
        val fixtureProfile = manager.currentProfile
        val initInspections = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        val projectProfile = InspectionProfileImpl("i18n project profile", InspectionToolRegistrar.getInstance(), manager)
            .apply { isProjectLevel = true }
        manager.addProfile(projectProfile)
        manager.setCurrentProfile(projectProfile)
        try {
            checksOff(projectProfile)
            block(projectProfile)
        } finally {
            manager.setCurrentProfile(fixtureProfile)
            manager.deleteProfile(projectProfile)
            InspectionProfileImpl.INIT_INSPECTIONS = initInspections
        }
    }

    private fun checksOff(profile: InspectionProfileImpl) =
        checks.filter { profile.getToolsOrNull(it, project) != null }.forEach { profile.setToolEnabled(it, false, project) }

    private fun InspectionProfileImpl.isEnabled(shortName: String): Boolean =
        isToolEnabled(HighlightDisplayKey.find(shortName))

    @Test
    fun `a ticked check is turned on in the project profile`() = onProjectProfile { profile ->
        RecommendedInspections.enable(project, listOf(RecommendedInspections.UNUSED_KEY))

        Assertions.assertTrue(profile.isEnabled(RecommendedInspections.UNUSED_KEY))
        Assertions.assertFalse(profile.isEnabled("I18nHardcodedJsxText"), "only the ticked check is turned on")
    }

    @Test
    fun `nothing ticked leaves the profile unchanged`() = onProjectProfile { profile ->
        RecommendedInspections.enable(project, emptyList())

        checks.forEach { Assertions.assertFalse(profile.isEnabled(it), it) }
    }

    @Test
    fun `the hard-coded text box turns on every registered hard-coded text check`() = onProjectProfile { profile ->
        RecommendedInspections.enable(project, RecommendedInspections.HARDCODED_TEXT)

        RecommendedInspections.HARDCODED_TEXT
            .filter { profile.getToolsOrNull(it, project) != null }
            .forEach { Assertions.assertTrue(profile.isEnabled(it), it) }
        Assertions.assertTrue(profile.isEnabled("I18nHardcodedJsxText"))
    }

    @Test
    fun `a project on a profile not its own is left alone`() {
        // The fixture's profile is the application-level kind a project set to an IDE profile uses.
        Assertions.assertFalse(manager.currentProfile.isProjectLevel)
        val before = RecommendedInspections.UNUSED_KEY.let { manager.currentProfile.getToolsOrNull(it, project)?.isEnabled }

        RecommendedInspections.enable(project, listOf(RecommendedInspections.UNUSED_KEY))

        Assertions.assertFalse(RecommendedInspections.canEnable(project))
        Assertions.assertEquals(before, manager.currentProfile.getToolsOrNull(RecommendedInspections.UNUSED_KEY, project)?.isEnabled)
    }
}
