package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class JsonContentGeneratorTest : PlatformBaseTest() {

    private val generator = JsonLocalization().contentGenerator()

    @Test
    fun testGenerateWithEmptyUnresolved_doesNotThrow() {
        myFixture.configureByText("test.json", "{}")
        val element = myFixture.file
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            generator.generate(element, fullKey, emptyList(), null)
        }
    }

    private fun rootObject(): JsonObject = (myFixture.file as JsonFile).topLevelValue as JsonObject

    private fun addEntry(key: String, value: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            generator.generateTranslationEntry(rootObject(), key, value)
        }
    }

    /**
     * The property is inserted first and the comma second: the corruption seen in the wild
     * was a file left with `"declined": "Declined",` and nothing after it, so whatever the
     * key, the object must read back whole — the comma exists only once the property does.
     */
    @Test
    fun appendedEntryKeepsTheObjectWellFormed() {
        myFixture.configureByText("test.json", """{
  "status": {
    "pending": "Pending"
  }
}""")
        addEntry("\${status}", "\"deposit-box:status.\${status}\"")
        myFixture.checkResult("""{
  "status": {
    "pending": "Pending"
  },
  "${'$'}{status}": "deposit-box:status.${'$'}{status}"
}""")
        assertEquals(2, rootObject().propertyList.size)
    }

    @Test
    fun sortedInsertionInFrontKeepsTheObjectWellFormed() = myFixture.runWithConfig(Config(extractSorted = true)) {
        myFixture.configureByText("test.json", """{
  "menu": "Menu"
}""")
        addEntry("about", "\"About\"")
        myFixture.checkResult("""{
  "about": "About",
  "menu": "Menu"
}""")
    }

    /** The value of [segment] in the root object, read by the JSON PSI; null when no such property parses. */
    private fun valueOf(segment: String): String? = (rootObject().findProperty(segment)?.value as? JsonStringLiteral)?.value

    /**
     * The characters a JSON string must escape, in a key segment: written as typed between quotes,
     * `"` ended the key early and `\` escaped what followed, and the new file did not parse.
     */
    @ParameterizedTest
    @ValueSource(strings = ["say \"hi\"", "back\\slash"])
    fun aNewFileWithASpecialKeySegmentReadsBackAsTyped(segment: String) {
        val content = generator.generateContent(listOf(Literal(segment), Literal("leaf")), "value")

        myFixture.configureByText("new.json", content)

        val nested = rootObject().findProperty(segment)?.value as? JsonObject
        Assertions.assertNotNull(nested, "generated:\n$content")
        Assertions.assertEquals("value", (nested!!.findProperty("leaf")?.value as? JsonStringLiteral)?.value, "generated:\n$content")
    }

    /** The same characters in a key added to an existing file, which `createProperty` pasted as typed. */
    @ParameterizedTest
    @ValueSource(strings = ["say \"hi\"", "back\\slash"])
    fun anEntryWithASpecialKeyAddedToAnExistingFileReadsBackAsTyped(segment: String) {
        myFixture.configureByText("test.json", "{\n  \"menu\": \"Menu\"\n}")

        addEntry(segment, "\"value\"")

        Assertions.assertEquals("value", valueOf(segment), myFixture.file.text)
        Assertions.assertEquals("Menu", valueOf("menu"), myFixture.file.text)
        Assertions.assertEquals(2, rootObject().propertyList.size, myFixture.file.text)
    }
}
