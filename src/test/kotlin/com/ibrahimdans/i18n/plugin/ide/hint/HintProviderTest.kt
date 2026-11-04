package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "→" link of a hover row. It used to carry the source's display path, resolved against the
 * project's base path: dead for a source inside an i18next configuration file
 * (`i18n.ts#translation`) and for a module outside the base path.
 */
class HintProviderTest : PlatformBaseTest() {

    private val i18nextConfig = """
        i18n.init({
          resources: {
            en: { translation: { home: { title: 'Home' } } },
            fr: { translation: { home: { title: 'Accueil' } } },
          },
        });
    """.trimIndent()

    /** The targets of the "→" links of the hint shown for the key under `<caret>` of [code], in row order. */
    private fun navigationLinksOf(code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/Home.ts", code).virtualFile)
        return ReadAction.compute<List<String>, RuntimeException> {
            val hint = HintProvider().generateDoc(null, myFixture.file.findElementAt(myFixture.caretOffset))!!
            Regex("href='psi_element://([^']*)'>&#8599;").findAll(hint).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        }
    }

    private fun follow(link: String): String? {
        val element = HintProvider().getDocumentationElementForLink(PsiManager.getInstance(project), link, null)
        waitForAsyncWork()
        return element?.containingFile?.name
    }

    @Test
    fun theLinkOfAnInlineSourceOpensTheConfigurationFileOnItsObject() = myFixture.runWithConfig(Config(jsConfiguration = "src/i18n.ts")) {
        addFileToProject("src/i18n.ts", i18nextConfig)
        val links = navigationLinksOf("t('home.ti<caret>tle')")
        assertEquals(2, links.size, "$links")
        assertTrue(links.all { it.startsWith(NAVIGATE_LINK_PREFIX) }, "$links")

        assertEquals("i18n.ts", follow(links.first()))

        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("i18n.ts", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertEquals('{', editor.document.charsSequence[editor.caretModel.offset], "the caret lands on the namespace's object")
    }

    @Test
    fun theLinkOfATranslationFileOpensIt() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        val link = navigationLinksOf("t('common:menu.ho<caret>me')").single()

        assertEquals("common.json", follow(link))
        assertEquals("common.json", FileEditorManager.getInstance(project).selectedFiles.single().name)
    }
}
