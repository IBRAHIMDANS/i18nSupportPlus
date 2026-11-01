package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The value the tool window shows for a JSON string is its text, not the way the file spells it:
 * `"Say \"hi\""` reads `Say "hi"`, a `\n` a line break, `\u00e9` an `é`.
 *
 * The table writes an edited cell through the JSON writer, which escapes the text again. Shown as
 * spelled, `"Say \"hi\""` came back from an edit as `"Say \\\"hi\\\""`: each edit escaped the
 * value once more. The YAML counterpart is [YamlScalarValuesTest].
 */
class JsonStringValuesTest : PlatformBaseTest() {

    private fun shown(key: String): String? =
        TranslationDataLoader.loadAllTranslations(project)["common:$key"]?.get("en")

    @Test
    fun escapedQuotesShowAsQuotes() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{ "title": "Say \"hi\"" }""")

        Assertions.assertEquals("Say \"hi\"", shown("title"))
    }

    @Test
    fun escapedLineBreakAndBackslashShowDecoded() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{ "title": "Line\nPath C:\\temp" }""")

        Assertions.assertEquals("Line\nPath C:\\temp", shown("title"))
    }

    @Test
    fun unicodeEscapeShowsItsCharacter() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{ "title": "Caf\u00e9" }""")

        Assertions.assertEquals("Café", shown("title"))
    }

    @Test
    fun json5SingleQuotedValueShowsItsText() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json5", "{ title: 'It\\'s' }")

        Assertions.assertEquals("It's", shown("title"))
    }

    @Test
    fun plainValueIsUnchanged() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{ "menu": { "home": "Home page" } }""")

        Assertions.assertEquals("Home page", shown("menu.home"))
    }

    @Test
    fun editedEscapedValueReadsBackAsTyped() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{ "title": "Say \"hi\"" }""")
        val edited = shown("title") + " here"

        val saved = TableViewModel().saveValue(project, "common:title", "en", edited)

        Assertions.assertEquals(true, saved)
        Assertions.assertEquals("Say \"hi\" here", shown("title"), "an edit must not escape the value a second time")
    }
}
