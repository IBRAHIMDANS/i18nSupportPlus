package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.search.searches.ReferencesSearch
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * vue-i18n's linked messages — `"Click @:common.save"` — in a translation value: the linked key
 * resolves to its translation, an unknown one is reported, and a key only linked is used.
 */
class LinkedMessageReferenceTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun messages(value: String) =
        """{ "common": { "save": "Save", "orphan": "Orphan" }, "button": "$value" }"""

    /** Opens `locales/en.json` holding [value] as `button`; [path] lets a module own it. */
    private fun open(value: String, path: String = "locales/en.json") {
        myFixture.configureFromExistingVirtualFile(addFileToProject(path, messages(value)).virtualFile)
    }

    private fun referenceAt(text: String): LinkedMessageReference? {
        val offset = myFixture.file.text.indexOf(text)
        Assertions.assertTrue(offset >= 0, "no '$text' in the file")
        return myFixture.file.findReferenceAt(offset + 1) as? LinkedMessageReference
    }

    private fun property(file: JsonFile, vararg path: String): JsonProperty =
        path.drop(1).fold((file.topLevelValue as JsonObject).findProperty(path.first())!!) { property, name ->
            (property.value as JsonObject).findProperty(name)!!
        }

    // ── Parsing: vue-i18n v9's message compiler, plus v8's `@:(key)` ─────────

    @Test
    fun `the syntax is vue-i18n's`() {
        fun keys(text: String) = LinkedMessages.parse(text).map { it.key }
        Assertions.assertEquals(listOf("common.save"), keys("Click @:common.save"))
        Assertions.assertEquals(listOf("common.save"), keys("@.lower:common.save"))
        Assertions.assertEquals(listOf("a", "b"), keys("@.upper:a and @.capitalize:b"))
        Assertions.assertEquals(listOf("common.save"), keys("Click @:(common.save)"))
        Assertions.assertEquals(listOf("common.save"), keys("@:{'common.save'}!"))
        Assertions.assertEquals(listOf("foo"), keys("Welcome (@.upper:foo)"))
        // vue-i18n v9 keeps a trailing dot in the key: `@:{'key'}.` is how a sentence ends on one.
        Assertions.assertEquals(listOf("common.save."), keys("Click @:common.save."))
        Assertions.assertEquals(listOf("a.b", "c"), keys("@:a.b|@:c"))
        Assertions.assertEquals(emptyList<String>(), keys("mail me@example.com"))
        Assertions.assertEquals(emptyList<String>(), keys("""\@:escaped"""))
        Assertions.assertEquals(emptyList<String>(), keys("@:{name}"))
        Assertions.assertEquals(emptyList<String>(), keys("@: spaced"))
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["Click @:common.save", "@.lower:common.save", "Click @:(common.save)", "@:{'common.save'}"])
    fun `a linked key resolves to its translation`(value: String) {
        open(value)
        read {
            val reference = referenceAt("common.save")
            Assertions.assertEquals("common.save", reference?.linkedKey, "$value carries no linked reference")
            Assertions.assertEquals("Save", reference!!.resolve()?.text?.unQuote())
            Assertions.assertEquals("common.save", reference.rangeInElement.substring(reference.element.text))
        }
    }

    @Test
    fun `find usages of a key lists the values linking to it`() {
        open("Click @:common.save")
        read {
            val save = property(myFixture.file as JsonFile, "common", "save")
            val found = ReferencesSearch.search(save).findAll().filterIsInstance<LinkedMessageReference>()
            Assertions.assertEquals(listOf("common.save"), found.map { it.linkedKey })
        }
    }

    // ── Unknown keys ──────────────────────────────────────────────────────────

    @Test
    fun `an unknown linked key is reported, a known one is not`() {
        open("Click @:common.save or @.upper:common.nope")
        val reported = myFixture.doHighlighting().filter { it.description == unresolved }
        Assertions.assertEquals(1, reported.size, "got ${reported.map { it.text }}")
        Assertions.assertEquals("common.nope", reported.single().text)
    }

    // ── Usages ────────────────────────────────────────────────────────────────

    @Test
    fun `a key only linked counts as used`() {
        open("Click @:common.save")
        read {
            val file = myFixture.file as JsonFile
            Assertions.assertTrue(TranslationKeyUsages.indirectCount(property(file, "common", "save").nameElement) > 0)
            Assertions.assertEquals(0, TranslationKeyUsages.indirectCount(property(file, "common", "orphan").nameElement))
        }
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    @Test
    fun `a vue-i18n module reads its links`() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "vue", rootDirectory = "apps/vue", preset = "vue-i18n")))
    ) {
        open("Click @:common.save", "apps/vue/locales/en.json")
        read { Assertions.assertEquals("Save", referenceAt("common.save")?.resolve()?.text?.unQuote()) }
    }

    @Test
    fun `a module of another framework reads no link`() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        open("Click @:common.save or @:common.nope", "apps/web/locales/en.json")
        read {
            Assertions.assertNull(referenceAt("common.save"))
            val file = myFixture.file as JsonFile
            Assertions.assertEquals(0, TranslationKeyUsages.indirectCount(property(file, "common", "save").nameElement))
        }
        Assertions.assertTrue(myFixture.doHighlighting().none { it.description == unresolved })
    }
}
