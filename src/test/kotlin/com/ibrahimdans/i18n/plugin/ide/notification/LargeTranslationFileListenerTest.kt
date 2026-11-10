package com.ibrahimdans.i18n.plugin.ide.notification

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LargeTranslationFileListenerTest : PlatformBaseTest() {

    /** The events the VFS publishes while [change] runs in a write action. */
    private fun eventsOf(change: () -> Unit): List<VFileEvent> {
        val events = mutableListOf<VFileEvent>()
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(published: List<VFileEvent>) {
                events += published
            }
        })
        WriteAction.runAndWait<Throwable>(change)
        return events
    }

    @Test
    fun savedFileAboveTheLimitIsChecked() {
        val en = addFileToProject("locales/en.json", """{"hello": "Hello"}""").virtualFile

        val events = eventsOf { en.setBinaryContent("""{"hello": "Hi"}""".toByteArray()) }

        assertEquals(setOf(en), LargeTranslationFileListener.largeFilesOf(events) { true })
    }

    @Test
    fun savedFileBelowTheLimitIsNotChecked() {
        val en = addFileToProject("locales/en.json", """{"hello": "Hello"}""").virtualFile

        val events = eventsOf { en.setBinaryContent("""{"hello": "Hi"}""".toByteArray()) }

        assertTrue(LargeTranslationFileListener.largeFilesOf(events) { false }.isEmpty())
    }

    @Test
    fun createdFileIsChecked() {
        val dir = addFileToProject("locales/en.json", "{}").virtualFile.parent

        val events = eventsOf { dir.createChildData(this, "fr.json") }

        assertEquals(listOf("fr.json"), LargeTranslationFileListener.largeFilesOf(events) { true }.map { it.name })
    }
}
