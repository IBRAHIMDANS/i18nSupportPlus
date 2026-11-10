package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFile
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.PlatformTestUtil
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * *Keep the key* writes the kept keys where they apply: the stored settings, or — when
 * `i18n-check.json` sets the list — nowhere, the file opened with a word on why.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeepKeyQuickFixTest : PlatformBaseTest() {

    private val settings: Settings get() = Settings.getInstance(project)
    private lateinit var stored: Config

    private val descriptor = mockk<ProblemDescriptor> { every { psiElement } returns null }

    @BeforeEach
    fun keepStored() {
        stored = settings.config()
    }

    @AfterEach
    fun restoreStored() {
        project.guessProjectDir()?.findChild(ProjectConfigFile.PATH)?.let { file ->
            ApplicationManager.getApplication().invokeAndWait { FileEditorManager.getInstance(project).closeFile(file) }
            WriteAction.runAndWait<Exception> { file.delete(this) }
        }
        settings.setConfig(stored)
    }

    @Test
    fun `the key is added to the stored list`() {
        settings.setConfig(settings.config().copy(keptKeys = "legacy.*"))

        keep("api.status")

        Assertions.assertEquals("legacy.*, api.status", settings.config().keptKeys)
    }

    @Test
    fun `a key already kept is not added twice`() {
        settings.setConfig(settings.config().copy(keptKeys = "api.status"))

        keep("api.status")

        Assertions.assertEquals("api.status", settings.config().keptKeys)
    }

    /** A config file that does not set the list leaves it to the stored settings, which the fix writes. */
    @Test
    fun `a config file without the list leaves it to the stored settings`() {
        myFixture.addFileToProject(ProjectConfigFile.PATH, """{ "keySeparator": "." }""")

        keep("api.status")

        Assertions.assertEquals("api.status", settings.config().keptKeys)
    }

    @Test
    fun `a list the config file sets is not written to the stored settings, the file is opened`() {
        myFixture.addFileToProject(ProjectConfigFile.PATH, """{ "keptKeys": "legacy.*" }""")
        val storedBefore = settings.keptKeys

        val seen = notified { keep("api.status") }

        Assertions.assertEquals(storedBefore, settings.keptKeys, "stored list untouched")
        Assertions.assertEquals("legacy.*", settings.config().keptKeys)
        val notification = seen.single()
        Assertions.assertEquals(NotificationType.WARNING, notification.type)
        Assertions.assertTrue(notification.content.contains("api.status"), notification.content)
        Assertions.assertTrue(notification.content.contains(ProjectConfigFile.PATH), notification.content)
        val file = settings.configFileOrNull()!!
        Assertions.assertTrue(FileEditorManager.getInstance(project).isFileOpen(file), "config file opened")
    }

    /** Applies the fix on the EDT, where the IDE applies it, and runs what it queues there. */
    private fun keep(key: String) {
        ApplicationManager.getApplication().invokeAndWait {
            KeepKeyQuickFix(key).applyFix(project, descriptor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
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
        return seen
    }
}
