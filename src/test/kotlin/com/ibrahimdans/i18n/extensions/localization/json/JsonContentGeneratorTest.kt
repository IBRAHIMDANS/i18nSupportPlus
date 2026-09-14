package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
