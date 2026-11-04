package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChangesDialog
import com.ibrahimdans.i18n.plugin.mcp.KeyMaintenanceTools
import com.ibrahimdans.i18n.plugin.mcp.TranslationAgentTools
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.guessProjectDir
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The modules `i18n-check.json` declares apply wherever the stored ones do, with nothing stored in
 * `.idea`: ten readers used to take the stored list alone, so a project configured by the file had
 * no preset, no call template, no reviews folder for its lone module and no module for the agents.
 */
class ProjectConfigFileModulesTest : PlatformBaseTest() {

    private val settings: Settings get() = Settings.getInstance(project)
    private lateinit var stored: Config

    private val vueModule = """{ "name": "vue", "rootDirectory": "apps/vue", "preset": "vue-i18n", "callTemplate": "translate({key})" }"""

    @BeforeEach
    fun storeNoModule() {
        stored = settings.config()
        settings.setConfig(stored.copy(modules = emptyList()))
    }

    @AfterEach
    fun restoreStored() {
        deleteConfigFile()
        settings.setConfig(stored)
    }

    @Test
    fun `without the file nor a stored module, nothing asks for a Config`() {
        assertFalse(settings.hasModules())
        assertNull(ModulePresets.presetOf(addFileToProject("apps/vue/src/A.js", "t('x')")))
    }

    @Test
    fun `a module the file declares has its preset and its call template`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "modules": [$vueModule] }""")
        val inside = addFileToProject("apps/vue/src/A.js", "t('x')")
        val outside = addFileToProject("apps/react/src/B.js", "t('x')")

        assertTrue(settings.hasModules())
        assertEquals("vue-i18n", ModulePresets.presetOf(inside))
        assertEquals("translate({key})", CallTemplate.of(inside))
        assertNull(ModulePresets.presetOf(outside))
        assertNull(CallTemplate.of(outside))
    }

    @Test
    fun `the file's list replaces the stored one, an empty list included`() {
        settings.setConfig(stored.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web"))))
        assertTrue(settings.hasModules())

        addFileToProject(ProjectConfigFile.PATH, """{ "modules": [] }""")

        assertFalse(settings.hasModules(), "the file says the project has no module")
        assertTrue(settings.config().modules.isEmpty())
    }

    /** A light fixture resolves no module root on disk: the lone module shares the project's folder, as the stored one does. */
    @Test
    fun `the lone module of the file and no module share the reviews folder`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "modules": [$vueModule] }""")
        val projectDir = project.guessProjectDir()!!
        myFixture.tempDirFixture.findOrCreateDir(ReviewLocation.DIRECTORY)
        val module = settings.config().modules.single()

        assertEquals(projectDir, ReviewLocation.rootOf(project, null))
        assertEquals(projectDir, ReviewLocation.rootOf(project, module))
    }

    @Test
    fun `the agents list the modules of the file`() {
        addFileToProject(
            ProjectConfigFile.PATH,
            """{ "modules": [{ "name": "web", "rootDirectory": "apps/web" }, { "name": "admin", "rootDirectory": "apps/admin" }] }"""
        )

        assertEquals(listOf("web", "admin"), KeyMaintenanceTools(project).scopes().map { it.first })
        assertEquals(listOf("web", "admin"), TranslationAgentTools(project).modules().map { it.name })
    }

    @Test
    fun `the changes dialog spells a key with the separators of the file`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")

        assertEquals("common:menu/home", TranslationChangesDialog.keyOf(settings.config(), "common", listOf("menu", "home")))
    }

    private fun deleteConfigFile() {
        val file = project.guessProjectDir()?.findChild(ProjectConfigFile.PATH) ?: return
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(file)?.let { ApplicationManager.getApplication().invokeAndWait { documents.reloadFromDisk(it) } }
        WriteAction.runAndWait<Exception> { file.delete(this) }
    }
}
