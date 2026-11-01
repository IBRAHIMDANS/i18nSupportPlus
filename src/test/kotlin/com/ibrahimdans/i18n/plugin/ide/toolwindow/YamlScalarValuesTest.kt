package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The value the tool window shows for a YAML scalar is its text, not the way the file spells it:
 * `'It''s'` reads "It's", a double-quoted `\n` a line break, a block its lines.
 *
 * The table writes an edited cell through the YAML writer, which spells the text again in the
 * value's quotes. Shown as spelled, `'It''s'` came back from an edit as `'''It''''s'''`: each
 * edit escaped the value once more.
 */
class YamlScalarValuesTest : PlatformBaseTest() {

    private val file = "locales/en/common.yml"

    private fun shown(key: String): String? =
        TranslationDataLoader.loadAllTranslations(project)["common:$key"]?.get("en")

    @Test
    fun singleQuotedValueShowsItsText() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "title: 'It''s'\n")

        Assertions.assertEquals("It's", shown("title"))
    }

    @Test
    fun doubleQuotedValueShowsItsEscapesDecoded() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "title: \"Line\\nSay \\\"hi\\\"\"\n")

        Assertions.assertEquals("Line\nSay \"hi\"", shown("title"))
    }

    @Test
    fun literalBlockShowsItsLines() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "title: |\n  First line\n  Second line\n")

        Assertions.assertEquals("First line\nSecond line\n", shown("title"))
    }

    @Test
    fun foldedBlockShowsItsFoldedText() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "title: >\n  First line\n  same paragraph\n")

        Assertions.assertEquals("First line same paragraph\n", shown("title"))
    }

    @Test
    fun plainValueIsUnchanged() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "menu:\n  home: Home page\n")

        Assertions.assertEquals("Home page", shown("menu.home"))
    }

    @Test
    fun editedQuotedValueReadsBackAsTyped() = myFixture.runWithConfig(Config()) {
        addFileToProject(file, "title: 'It''s'\n")
        val edited = shown("title") + " here"

        val saved = TableViewModel().saveValue(project, "common:title", "en", edited)

        Assertions.assertEquals(true, saved)
        Assertions.assertEquals("It's here", shown("title"), "an edit must not escape the value a second time")
    }
}
