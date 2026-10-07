package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
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
