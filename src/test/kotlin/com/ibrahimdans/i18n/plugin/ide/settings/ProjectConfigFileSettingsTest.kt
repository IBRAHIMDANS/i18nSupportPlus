package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.PlatformTestUtil
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `i18n-check.json` at the project's root, read by the IDE: what it sets wins over the stored
 * settings, is never written back into them, and is read again when the file changes — its open
 * document once the typing in it pauses, at once after an undo.
 */
class ProjectConfigFileSettingsTest : PlatformBaseTest() {

    private val settings: Settings get() = Settings.getInstance(project)
    private lateinit var stored: Config

    @BeforeEach
    fun keepStored() {
        stored = settings.config()
    }

    @AfterEach
    fun restoreStored() {
        deleteConfigFile()
        settings.setConfig(stored)
        settings.endTypingPause()
    }

    @Test
    fun `a setting the file names wins over the stored one`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/", "translationsRoot": "src/locales" }""")

        val config = settings.config()

        assertEquals("/", config.keySeparator)
        assertEquals("src/locales", config.translationsRoot)
        assertEquals(stored.defaultNs, config.defaultNs)
        assertEquals(mapOf("keySeparator" to "/", "translationsRoot" to "src/locales"), settings.configFileSettings())
    }

    @Test
    fun `a setting the file names is never written into the stored settings`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")

        settings.setConfig(settings.config().copy(keySeparator = "#", defaultNs = "app"))
        deleteConfigFile()

        assertEquals(stored.keySeparator, settings.config().keySeparator)
        assertEquals("app", settings.config().defaultNs)
    }

    @Test
    fun `a file saved again is read again`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        assertEquals("/", settings.config().keySeparator)

        val file = settings.configFileOrNull()!!
        WriteAction.runAndWait<Exception> { VfsUtil.saveText(file, """{ "keySeparator": "#" }""") }

        assertEquals("#", settings.config().keySeparator)
    }

    @Test
    fun `a file created after the settings were read is found`() {
        settings.config()

        addFileToProject(ProjectConfigFile.PATH, """{ "defaultNs": "common" }""")

        assertEquals("common", settings.config().defaultNs)
    }

    @Test
    fun `an unsaved document is read before the file, once the typing in it pauses`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        assertEquals("/", settings.config().keySeparator)

        editUnsaved("""{ "keySeparator": "#" }""")

        Assertions.assertEquals("/", settings.config().keySeparator, "a keystroke waits for the pause")
        settings.endTypingPause()
        assertEquals("#", settings.config().keySeparator)
    }

    /** The pause is the time's: what the typing left is read without another change — a daemon pass after the pause reads it. */
    @Test
    fun `the typing pause ends by itself`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        assertEquals("/", settings.config().keySeparator)

        editUnsaved("""{ "keySeparator": "#" }""")

        assertEquals("/", settings.config().keySeparator)
        PlatformTestUtil.waitWithEventsDispatching("the typing pause", { settings.config().keySeparator == "#" }, 5)
    }

    /** The undo of a namespace rename, which saved the file: the settings follow the undo at once, before any save. */
    @Test
    fun `an undone edit is read at once, unsaved`() {
        val file = addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""").virtualFile
        myFixture.openFileInEditor(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("""{ "keySeparator": "#" }""") }
        FileDocumentManager.getInstance().saveDocument(document)
        assertEquals("#", settings.config().keySeparator)

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document), "the undo leaves the document unsaved")
        assertEquals("/", settings.config().keySeparator)
    }

    @Test
    fun `an unsaved document that does not read leaves the file in charge`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        assertEquals("/", settings.config().keySeparator)

        editUnsaved("""{ "keySeparator": "#" """)
        settings.endTypingPause()

        assertEquals("/", settings.config().keySeparator)
    }

    @Test
    fun `a file that does not read leaves the stored settings, without an exception`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeperator": "/" }""")

        assertEquals(stored, settings.config())
        assertTrue(settings.configFileSettings().isEmpty())
    }

    @Test
    fun `the preview locale of the file is left to each developer`() {
        settings.setConfig(stored.copy(previewLocale = "de"))
        addFileToProject(ProjectConfigFile.PATH, """{ "previewLocale": "fr" }""")

        assertEquals("de", settings.config().previewLocale)
        assertTrue(settings.configFileSettings().isEmpty())
    }

    @Test
    fun `an exported file reads back as the same settings`() {
        val config = stored.copy(
            keySeparator = "/",
            ignoredLocales = "de",
            modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr")),
        )
        val dir = project.guessProjectDir()!!

        ProjectConfigFileExport.write(project, dir, null, ProjectConfigFile.export(config))

        assertEquals(config, settings.config())
    }

    @Test
    fun `the file opens once the queue runs it, not while a dialog would hide it`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        val file = settings.configFileOrNull()!!
        val editors = FileEditorManager.getInstance(project)
        var queued: Runnable? = null

        ProjectConfigFileExport.open(project, file) { queued = it }

        assertFalse(editors.isFileOpen(file), "the file must wait for the settings dialog to close")
        ApplicationManager.getApplication().invokeAndWait { queued!!.run() }
        assertTrue(editors.isFileOpen(file))
        ApplicationManager.getApplication().invokeAndWait { editors.closeFile(file) }
    }

    @Test
    fun `the setup wizard and the settings page write the same file`() {
        settings.setConfig(stored.copy(keySeparator = "/", ignoredLocales = "de"))

        val file = ProjectConfigFileExport.exportSettings(project)

        assertEquals(ProjectConfigFile.export(settings.config()), VfsUtilCore.loadText(file!!))
        assertEquals("/", settings.config().keySeparator)
    }

    @Test
    fun `the settings pages offer to edit the file only when the project has one`() {
        assertEquals(null, ProjectConfigFileExport.viewFor(project).edit)

        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")

        val view = ProjectConfigFileExport.viewFor(project)
        assertTrue(view.edit != null)
        assertEquals(mapOf("keySeparator" to "/"), view.held)
    }

    @Test
    fun `a read of the file cancelled on the way is cancelled, not reported as a file that does not read`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")

        val notifications = notified {
            whileParsing({ true }) {
                assertThrows(ProcessCanceledException::class.java) { settings.configFileChanged() }
            }
        }

        assertTrue(notifications.isEmpty(), "a cancelled read must not be reported: $notifications")
        assertEquals("/", settings.config().keySeparator)
    }

    @Test
    fun `a read of the unsaved document cancelled on the way is cancelled, not left to the file`() {
        addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "/" }""")
        editUnsaved("""{ "keySeparator": "#" }""")
        settings.endTypingPause()

        whileParsing({ "#" in it }) {
            assertThrows(ProcessCanceledException::class.java) { settings.configFileChanged() }
        }

        assertEquals("#", settings.config().keySeparator)
    }

    /** Runs [block] while [ProjectConfigFile.parse] is cancelled for the texts that match [cancelled]. */
    private fun whileParsing(cancelled: (String) -> Boolean, block: () -> Unit) {
        mockkObject(ProjectConfigFile)
        try {
            every { ProjectConfigFile.parse(match(cancelled)) } throws ProcessCanceledException()
            block()
        } finally {
            unmockkObject(ProjectConfigFile)
        }
    }

    /** The notifications posted while [run] runs. */
    private fun notified(run: () -> Unit): List<Notification> {
        val seen = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                seen += notification
            }
        })
        run()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return seen
    }

    /** Changes the document of `i18n-check.json` without saving it, as typing does. */
    private fun editUnsaved(text: String) {
        val file = settings.configFileOrNull()!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    private fun deleteConfigFile() {
        val file = project.guessProjectDir()?.findChild(ProjectConfigFile.PATH) ?: return
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(file)?.let { ApplicationManager.getApplication().invokeAndWait { documents.reloadFromDisk(it) } }
        WriteAction.runAndWait<Exception> { file.delete(this) }
    }
}
