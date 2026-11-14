package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The commit option: the reference keys a commit changes that a locale of the commit does not follow. */
class TranslationCheckinHandlerTest : PlatformBaseTest() {

    @AfterEach
    fun resetOption() = CommitTranslationCheck.setEnabled(project, false)

    /** The path of [file] that finds it again: the fixture's in-memory files are not on the local file system. */
    private fun pathOf(file: VirtualFile): FilePath = object : FilePath by VcsUtil.getFilePath(file) {
        override fun getVirtualFile() = file
    }

    /** [file] as committed, against [before] — null for a file the commit adds. */
    private fun change(file: PsiFile, before: String?): Change {
        val path = pathOf(file.virtualFile)
        val base = before?.let { text ->
            object : ContentRevision {
                override fun getContent() = text
                override fun getFile() = path
                override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
            }
        }
        return Change(base, CurrentContentRevision(path))
    }

    /** What the check finds in [changes], computed the way the handler does, without its coroutines. */
    private fun problemIn(changes: List<Change>) = ReadAction.compute<LaggingTranslationsProblem?, RuntimeException> {
        val files = CommitTranslationCheck.translationFiles(project, changes)
            .map { it.psi to it.change.beforeRevision?.content }
        CommitTranslationCheck.problemOf(CommitTranslationCheck.compare(project, files)) as LaggingTranslationsProblem?
    }

    @Test
    fun aCommitWithoutTranslationFilesRaisesNothing() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        val readme = addFileToProject("README.md", "new")

        val changes = listOf(change(readme, "old"))

        assertTrue(ReadAction.compute<Boolean, RuntimeException> {
            CommitTranslationCheck.translationFiles(project, changes).isEmpty()
        })
        assertNull(problemIn(changes))
    }

    @Test
    fun aKeyAddedToTheReferenceAloneNamesTheLaggingLocale() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save", "cancel": "Cancel"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        addFileToProject("locales/de/common.json", """{"save": "Speichern", "cancel": "Abbrechen"}""")

        val problem = problemIn(listOf(change(en, """{"save": "Save"}""")))!!

        assertEquals(1, problem.keyCount)
        // `de` already holds the key, but did not follow this commit's change: it lags too.
        assertEquals(listOf("de", "fr"), problem.locales)
        assertTrue(problem.text.contains("en"), problem.text)
        assertTrue(problem.text.contains("de, fr"), problem.text)
    }

    @Test
    fun aKeyAddedToEveryLocaleRaisesNothing() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save", "cancel": "Cancel"}""")
        val fr = addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")

        val changes = listOf(change(en, """{"save": "Save"}"""), change(fr, """{"save": "Enregistrer"}"""))

        assertNull(problemIn(changes))
    }

    @Test
    fun theOptionIsOffByDefaultAndRemembered() {
        val handler = TranslationCheckinHandler(project)
        assertFalse(handler.isEnabled())

        CommitTranslationCheck.setEnabled(project, true)

        assertTrue(TranslationCheckinHandler(project).isEnabled())
    }

    @Test
    fun anUncheckedOptionComputesNothing() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val en = addFileToProject("locales/en/common.json", """{"save": "Save", "cancel": "Cancel"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        var read = false
        val base = object : ContentRevision {
            override fun getContent(): String { read = true; return """{"save": "Save"}""" }
            override fun getFile() = pathOf(en.virtualFile)
            override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
        }
        val commit = commitOf(listOf(Change(base, CurrentContentRevision(pathOf(en.virtualFile)))))

        val problem = runBlocking { TranslationCheckinHandler(project).runCheck(commit) }

        assertNull(problem)
        assertFalse(read, "the before-content of the commit was read with the option off")
    }

    private fun commitOf(changes: List<Change>) = object : CommitInfo {
        override val commitContext = CommitContext()
        override val isVcsCommit = true
        override val executor = null
        override val committedChanges = changes
        override val affectedVcses = emptyList<com.intellij.openapi.vcs.AbstractVcs>()
        override val commitMessage = ""
        override val commitActionText = "Commit"
    }
}
