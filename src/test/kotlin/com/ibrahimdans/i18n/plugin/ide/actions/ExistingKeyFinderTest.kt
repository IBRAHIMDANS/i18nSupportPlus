package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The keys an extraction may reuse instead of creating a twin: those whose value in the reference
 * locale is the extracted text.
 */
class ExistingKeyFinderTest : PlatformBaseTest() {

    private fun find(text: String, caller: PsiFile, config: Config = Config()): List<String> {
        var keys = emptyList<String>()
        myFixture.runWithConfig(config) { read { keys = ExistingKeyFinder.find(text, caller) } }
        return keys
    }

    private fun caller(path: String = "src/App.js"): PsiFile = addFileToProject(path, "const a = 'x';")

    @Test
    fun `no key holds the text`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        assertEquals(emptyList<String>(), find("Cancel", caller()))
    }

    @Test
    fun `one key holds the text, compared trimmed and case-sensitively`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": " Save ", "lower": "save"}}""")
        assertEquals(listOf("common:actions.save"), find("Save", caller()))
    }

    @Test
    fun `several keys across namespaces, the default namespace written without prefix`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("locales/en/translation.json", """{"form": {"submit": "Save"}}""")
        assertEquals(
            setOf("common:actions.save", "form.submit"),
            find("Save", caller()).toSet()
        )
    }

    @Test
    fun `other locales than the reference one are not read`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("locales/fr/common.json", """{"actions": {"save": "Enregistrer"}}""")
        assertEquals(emptyList<String>(), find("Enregistrer", caller()))
        assertEquals(listOf("common:actions.save"), find("Enregistrer", caller("src/Other.js"), Config(previewLocale = "fr")))
    }

    @Test
    fun `keys are spelled with the configured separators`() {
        val path = listOf("actions", "save")
        assertEquals("common:actions.save", ExistingKeyFinder.spell("common", path, Config()))
        assertEquals("common.actions.save", ExistingKeyFinder.spell("common", path, Config(firstComponentNs = true)))
        assertEquals("common|actions_save", ExistingKeyFinder.spell("common", path, Config(nsSeparator = "|", keySeparator = "_")))
        assertEquals("app.title", ExistingKeyFinder.spell("common", listOf("app.title"), Config(flatKeys = true)))
    }

    @Test
    fun `a key of another module is ignored`() {
        addFileToProject("apps/web/locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("apps/admin/locales/en/common.json", """{"buttons": {"save": "Save"}}""")
        val config = Config(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "en"),
                ModuleConfig(name = "admin", rootDirectory = "apps/admin", referenceLocale = "en"),
            )
        )
        assertEquals(listOf("common:actions.save"), find("Save", caller("apps/web/src/App.js"), config))
    }

    @Test
    fun `several texts are looked up at once, keyed by the trimmed text`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save", "cancel": "Cancel"}}""")
        addFileToProject("locales/en/translation.json", """{"form": {"submit": "Save"}}""")
        val caller = caller()
        var found = emptyMap<String, List<String>>()
        myFixture.runWithConfig(Config()) {
            read { found = ExistingKeyFinder.findAll(listOf(" Save ", "Cancel", "Missing", ""), caller) }
        }
        assertEquals(setOf("Save", "Cancel"), found.keys, "A text no key holds is absent")
        assertEquals(setOf("common:actions.save", "form.submit"), found.getValue("Save").toSet())
        assertEquals(listOf("common:actions.cancel"), found.getValue("Cancel"))
    }
}
