package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.vcsUtil.VcsUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The working copy's side of the diff: a file's text before the change against the file as it is. */
class LocalTranslationChangesTest : PlatformBaseTest() {

    private fun versions(path: String, before: String?): FileVersions? = ReadAction.compute<FileVersions?, RuntimeException> {
        LocalTranslationChanges.versionsOf(PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!, before)
    }

    @Test
    fun aChangedTranslationFileIsComparedWithItsPreviousText() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home page", "new": "New"}}""")
        addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil"}}""")
        val en = versions("locales/en/common.json", """{"menu": {"home": "Home"}}""")!!
        assertEquals("common", en.namespace)
        assertEquals("en", en.locale)

        val found = ReadAction.compute<TranslationChanges, RuntimeException> { LocalTranslationChanges.compare(project, listOf(en)) }
        assertEquals(listOf(listOf("menu", "home"), listOf("menu", "new")), found.changes.map { it.path })
        assertEquals(setOf("fr"), found.lagging.map { it.locale }.toSet())
    }

    /**
     * The path of [file] that finds it again: a plain [FilePath] looks it up in the local file
     * system, where the test fixture's in-memory files are not.
     */
    private fun pathOf(file: VirtualFile): FilePath = object : FilePath by VcsUtil.getFilePath(file) {
        override fun getVirtualFile() = file
    }

    /** A file's content at the base of a comparison. */
    private fun baseRevision(text: String, path: FilePath) = object : ContentRevision {
        override fun getContent() = text
        override fun getFile() = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    /** Any source of changes will do — a comparison with a branch hands over the same [Change]s. */
    @Test
    fun changesFromAnySourceAreCollected() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val en = addFileToProject("locales/en/common.json", """{"menu": {"home": "Home page"}}""")
        val readme = addFileToProject("README.md", "new")
        val changes = listOf(
            Change(baseRevision("""{"menu": {"home": "Home"}}""", pathOf(en.virtualFile)), CurrentContentRevision(pathOf(en.virtualFile))),
            Change(baseRevision("old", pathOf(readme.virtualFile)), CurrentContentRevision(pathOf(readme.virtualFile))),
        )

        val found = ReadAction.compute<TranslationChanges, RuntimeException> { LocalTranslationChanges.collect(project, changes) }

        assertEquals(listOf(TranslationChange.Kind.MODIFIED), found.changes.map { it.kind })
        assertEquals(listOf("menu", "home"), found.changes.single().path)
    }

    @Test
    fun anAddedFileHasNoPreviousKeys() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        assertEquals(emptyMap<List<String>, String>(), versions("locales/en/common.json", null)!!.before)
    }

    @Test
    fun aFileThatIsNotATranslationIsLeftOut() {
        addFileToProject("package.json", """{"name": "app"}""")
        assertNull(versions("package.json", """{"name": "old"}"""))
    }
}
