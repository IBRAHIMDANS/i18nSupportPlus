package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.check.ValueProblem
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * An agent checks a project's translations, finds where a key is used, and renames, creates or deletes
 * keys through the plugin's own steps: what a user would be refused in the IDE is refused, and each
 * write is one undoable command.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeyMaintenanceToolsTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    private fun tools() = KeyMaintenanceTools(project)

    /** A key created as `i18n_create_key` creates it: planned under a read action off the EDT, written on the EDT, then read back. */
    private fun KeyMaintenanceTools.create(module: String?, key: String, values: Map<String, String>, allowDuplicate: Boolean = false) =
        readOffEdt { planCreate(module, key, values, allowDuplicate) }.let { plan ->
            applyCreate(plan)
            ReadAction.compute<KeyMaintenanceTools.CreateResult, RuntimeException> { created(plan) }
        }

    private fun seed(): Pair<PsiFile, PsiFile> {
        val en = myFixture.addFileToProject(
            "locales/en/common.json",
            """{"title": "Title", "save": "Save {{what}}", "unused": "Unused", "kept": "Kept", "button": {"ok": "OK"},
               "item_one": "{{count}} item", "item_other": "{{count}} items"}"""
        )
        val fr = myFixture.addFileToProject(
            "locales/fr/common.json",
            """{"title": "Titre", "save": "", "extra": "En trop", "button": {"ok": "D'accord"}, "item_one": "{{count}} élément"}"""
        )
        myFixture.addFileToProject(
            "src/App.js",
            "export const a = (t) => [\n  t('common:title'),\n  t('common:save'),\n  t('common:button.ok'),\n  t('common:item', { count: 2 }),\n];"
        )
        return en to fr
    }

    private fun text(file: PsiFile): String = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!.text

    private fun undo(file: PsiFile) {
        myFixture.openFileInEditor(file.virtualFile)
        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)
    }

    @Test
    fun `the check lists what each locale lacks, holds in excess or leaves blank, as i18n-check does`() = myFixture.runWithConfig(config) {
        seed()

        val check = ReadAction.compute<List<KeyMaintenanceTools.ModuleCheck>, RuntimeException> { tools().check(null) }.single()

        Assertions.assertEquals("en", check.reference)
        val fr = check.locales.single { it.locale == "fr" }
        Assertions.assertEquals(listOf("common:item_other", "common:kept", "common:unused"), fr.missing)
        Assertions.assertEquals(listOf("common:extra"), fr.orphans)
        Assertions.assertEquals(listOf("common:save"), fr.empty)
        Assertions.assertNull(check.unused, "the code is searched on demand only")
    }

    @Test
    fun `the check also gives what is wrong with the values, as i18n-check does`() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", """{"greet": "Hello {{name}}, {{count}} new", "ok": "OK"}""")
        myFixture.addFileToProject("locales/fr/common.json", """{"greet": "Salut {{count}} nouveaux", "ok": "D'accord"}""")

        val check = ReadAction.compute<List<KeyMaintenanceTools.ModuleCheck>, RuntimeException> { tools().check(null) }.single()

        Assertions.assertEquals(
            listOf(ValueProblem("common:greet", "Placeholder '{name}' of the reference locale is missing")),
            check.values["fr"],
        )
        Assertions.assertNull(check.values["en"], "a locale without a problem is left out")
        Assertions.assertEquals(1, check.problems)
    }

    @Test
    fun `the unused keys are those of the reference no code uses, the keep list left out`() =
        myFixture.runWithConfig(config.copy(keptKeys = "common:kept")) {
            seed()

            val check = ReadAction.compute<List<KeyMaintenanceTools.ModuleCheck>, RuntimeException> { tools().check(null) }.single()
            val withUnused = tools().withUnused(check)

            Assertions.assertEquals(listOf("common:unused"), withUnused.unused)
            Assertions.assertEquals(check.problems + 1, withUnused.problems)
        }

    @Test
    fun `the locales the project does not check are left out of the check`() = myFixture.runWithConfig(config.copy(ignoredLocales = "fr")) {
        seed()

        val check = ReadAction.compute<List<KeyMaintenanceTools.ModuleCheck>, RuntimeException> { tools().check(null) }.single()

        Assertions.assertEquals(listOf("en"), check.locales.map { it.locale })
    }

    @Test
    fun `the usages of a key come with their file, line and code`() = myFixture.runWithConfig(config) {
        seed()

        val page = tools().usages(null, "common:save", 10)

        Assertions.assertTrue(page.defined)
        Assertions.assertEquals(1, page.total)
        val usage = page.usages.single()
        Assertions.assertTrue(usage.file.endsWith("src/App.js"), usage.file)
        Assertions.assertEquals(3, usage.line)
        Assertions.assertEquals("t('common:save'),", usage.code)
        Assertions.assertEquals(0, tools().usages(null, "common:unused", 10).total)
    }

    private fun find(query: String, locale: String? = null, limit: Int = 20) =
        ReadAction.compute<KeyMaintenanceTools.FoundKeys, RuntimeException> { tools().find(null, query, locale, limit) }

    @Test
    fun `a key prefix finds the keys under it, with their value in every locale`() = myFixture.runWithConfig(config) {
        seed()

        val found = find("common:button")

        Assertions.assertEquals(KeyMaintenanceTools.BY_KEY, found.match)
        Assertions.assertEquals(listOf("common:button.ok"), found.keys.map { it.key })
        Assertions.assertEquals(mapOf("en" to "OK", "fr" to "D'accord"), found.keys.single().values)
    }

    @Test
    fun `a key pattern finds the keys it matches, at most the limit asked`() = myFixture.runWithConfig(config) {
        seed()

        val found = find("common:item_*", limit = 1)

        Assertions.assertEquals(KeyMaintenanceTools.BY_KEY, found.match)
        Assertions.assertEquals(2, found.total)
        Assertions.assertEquals(listOf("common:item_one"), found.keys.map { it.key })
    }

    @Test
    fun `a text no key name matches is searched in the values, equal values first, then whole words`() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", """{"a": "Save changes", "b": "save", "c": "Unsaved", "d": "Cancel"}""")
        myFixture.addFileToProject("locales/fr/common.json", """{"a": "Enregistrer les modifications", "b": "Enregistrer"}""")

        val found = find("Save")

        Assertions.assertEquals(KeyMaintenanceTools.BY_VALUE, found.match)
        Assertions.assertEquals("en", found.locale)
        Assertions.assertEquals(listOf("common:b", "common:a", "common:c"), found.keys.map { it.key })
        Assertions.assertEquals(listOf("common:b"), find("enregistrer", locale = "fr").keys.map { it.key }.take(1))
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { find("Save", locale = "ja") }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { find("  ") }
    }

    @Test
    fun `a key found comes with how often the code uses it`() = myFixture.runWithConfig(config) {
        seed()

        val found = tools().withUsages(find("common:t"))

        Assertions.assertEquals(listOf("common:title" to 1), found.keys.map { it.key to it.usages })
        Assertions.assertEquals(0, tools().withUsages(find("common:unused")).keys.single().usages)
    }

    @Test
    fun `a key whose reference text another key holds is created only when the agent insists`() = myFixture.runWithConfig(config) {
        val (en, _) = seed()

        val refused = tools().create(null, "common:heading", mapOf("en" to "Title"))

        Assertions.assertEquals(emptyList<String>(), refused.written)
        Assertions.assertEquals(listOf("common:title"), refused.existing)
        Assertions.assertTrue(refused.refused!!.startsWith(KeyMaintenanceTools.DUPLICATE), refused.refused)
        Assertions.assertTrue("heading" !in text(en), text(en))

        val created = tools().create(null, "common:heading", mapOf("en" to "Title"), allowDuplicate = true)

        Assertions.assertEquals(listOf("en"), created.written)
        Assertions.assertEquals(listOf("common:title"), created.existing)
        Assertions.assertNull(created.refused)
        Assertions.assertTrue("\"heading\": \"Title\"" in text(en), text(en))
    }

    @Test
    fun `a rename moves the key in every locale and call site, in one undoable command`() = myFixture.runWithConfig(config) {
        val (en, fr) = seed()
        val app = myFixture.findFileInTempDir("src/App.js")

        val plan = ReadAction.compute<KeyMaintenanceTools.RenamePlan, RuntimeException> { tools().planRename(null, "common:title", "common:heading") }
        val result = tools().applyRename(plan)

        Assertions.assertEquals(2, result.files)
        Assertions.assertEquals(1, result.callSites)
        Assertions.assertTrue("\"heading\": \"Title\"" in text(en), text(en))
        Assertions.assertTrue("\"heading\": \"Titre\"" in text(fr), text(fr))
        Assertions.assertTrue("t('common:heading')" in FileDocumentManager.getInstance().getDocument(app)!!.text)

        undo(en)
        Assertions.assertTrue("\"title\": \"Title\"" in text(en) && "\"title\": \"Titre\"" in text(fr), "one Ctrl+Z: ${text(en)} ${text(fr)}")
    }

    @Test
    fun `a plural group is renamed whole, its suffixes kept`() = myFixture.runWithConfig(config) {
        val (en, _) = seed()

        val plan = ReadAction.compute<KeyMaintenanceTools.RenamePlan, RuntimeException> { tools().planRename(null, "common:item", "common:entry") }
        tools().applyRename(plan)

        Assertions.assertTrue("\"entry_one\"" in text(en) && "\"entry_other\"" in text(en), text(en))
    }

    @Test
    fun `a rename onto an existing key, to another level, or of a plural form alone is refused`() = myFixture.runWithConfig(config) {
        seed()

        fun plan(key: String, newKey: String) = ReadAction.compute<KeyMaintenanceTools.RenamePlan, RuntimeException> { tools().planRename(null, key, newKey) }

        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { plan("common:title", "common:save") }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { plan("common:title", "common:button.title") }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { plan("common:title", "other:title") }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { plan("common:item_one", "common:entry_one") }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { plan("common:missing", "common:found") }
    }

    @Test
    fun `a key is created in each locale with the placeholders of its reference value`() = myFixture.runWithConfig(config) {
        val (en, fr) = seed()

        val result = tools().create(null, "common:archive", mapOf("en" to "Archive {{name}}", "fr" to "Archiver"))

        Assertions.assertEquals(listOf("en"), result.written)
        Assertions.assertTrue(result.rejected.getValue("fr").startsWith(KeyMaintenanceTools.PLACEHOLDERS), result.rejected.toString())
        Assertions.assertTrue("\"archive\": \"Archive {{name}}\"" in text(en), text(en))
        Assertions.assertTrue("archive" !in text(fr), text(fr))

        undo(en)
        Assertions.assertTrue("archive" !in text(en), "one Ctrl+Z: ${text(en)}")
    }

    @Test
    fun `a key held already, a level, a key under a value or one without a reference value is refused`() = myFixture.runWithConfig(config) {
        seed()

        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().create(null, "common:title", mapOf("en" to "T")) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().create(null, "common:button", mapOf("en" to "B")) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().create(null, "common:title.sub", mapOf("en" to "S")) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().create(null, "common:fresh", mapOf("fr" to "Neuf")) }
        Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().create(null, "common:fresh", mapOf("en" to "New", "ja" to "新")) }
    }

    @Test
    fun `a key whose files change between the plan and the write is refused, nothing created`() = myFixture.runWithConfig(config) {
        val (en, _) = seed()
        val plan = readOffEdt { tools().planCreate(null, "common:archive", mapOf("en" to "Archive")) }

        edit(en, "\"title\": \"Title\"" to "\"title\": \"Title\", \"archive\": \"Stored\"")
        val refused = Assertions.assertThrows(TranslationAgentTools.Refused::class.java) { tools().applyCreate(plan) }

        Assertions.assertEquals(KeyMaintenanceTools.CREATE_CHANGED, refused.message)
        Assertions.assertTrue("\"archive\": \"Stored\"" in text(en) && "\"Archive\"" !in text(en), text(en))
    }

    private fun edit(file: PsiFile, replacement: Pair<String, String>) = WriteCommandAction.runWriteCommandAction(project) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        document.setText(document.text.replace(replacement.first, replacement.second))
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    @Test
    fun `only a key no code uses is deleted, in one undoable command`() = myFixture.runWithConfig(config.copy(keptKeys = "common:kept")) {
        val (en, _) = seed()

        val (deletable, rejected) = tools().deletable(null, listOf("common:unused", "common:title", "common:kept", "common:nowhere"))
        val result = tools().delete(null, deletable, rejected)

        Assertions.assertEquals(listOf("common:unused"), result.deleted)
        Assertions.assertEquals(1, result.entries)
        Assertions.assertTrue(result.rejected.getValue("common:title").startsWith("Still used"), result.rejected.toString())
        Assertions.assertEquals(KeyMaintenanceTools.KEPT, result.rejected["common:kept"])
        Assertions.assertEquals(KeyMaintenanceTools.UNKNOWN_KEY, result.rejected["common:nowhere"])
        Assertions.assertTrue("\"unused\"" !in text(en) && "\"title\"" in text(en), text(en))

        undo(en)
        Assertions.assertTrue("\"unused\": \"Unused\"" in text(en), "one Ctrl+Z: ${text(en)}")
    }
}
