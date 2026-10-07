package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratePseudoLocaleActionTest : PlatformBaseTest() {

    private fun generate(): List<String> {
        val files = ReadAction.compute<List<GeneratePseudoLocaleAction.PseudoFile>, RuntimeException> {
            GeneratePseudoLocaleAction.plan(project, GeneratePseudoLocaleAction.DEFAULT_LOCALE)
        }
        return GeneratePseudoLocaleAction.write(project, files)
    }

    private fun textAt(path: String): String = ReadAction.compute<String, RuntimeException> {
        String(myFixture.findFileInTempDir(path)!!.contentsToByteArray(), Charsets.UTF_8)
    }

    @Test
    fun aNamespaceFolderGetsItsPseudoLocaleFolder() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}, "hi": "Hi {{name}}"}""")
        myFixture.addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil"}, "hi": "Salut {{name}}"}""")

        val written = generate()

        assertEquals(1, written.size, "$written")
        assertTrue(written.single().endsWith("locales/en-XA/common.json"), "$written")
        assertEquals(
            "{\n  \"menu\": {\n    \"home\": \"[Ĥöɱé ··]\"\n  },\n  \"hi\": \"[Ĥî {{name}} ·]\"\n}\n",
            textAt("locales/en-XA/common.json")
        )
    }

    @Test
    fun aFileNamedAfterItsLocaleGetsASibling() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("i18n/en.json", """{"save": "Save"}""")

        generate()

        assertEquals("{\n  \"save\": \"[Šáṽé ··]\"\n}\n", textAt("i18n/en-XA.json"))
    }

    @Test
    fun aYamlFileStaysYaml() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.yaml", "menu:\n  home: Home\n")

        generate()

        assertEquals("\"menu\":\n  \"home\": \"[Ĥöɱé ··]\"\n", textAt("locales/en-XA/common.yaml"))
    }

    @Test
    fun regeneratingGivesTheSameFile() = myFixture.runWithConfig(Config()) {
        myFixture.addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        generate()
        val first = textAt("locales/en-XA/common.json")

        val second = generate()

        assertEquals(1, second.size, "the pseudo-locale itself is never taken for a reference: $second")
        assertEquals(first, textAt("locales/en-XA/common.json"))
    }
}
