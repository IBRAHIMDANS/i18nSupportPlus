package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Flutter ARB catalogue keeps each `@key` block next to its key and `@@locale` first when sorted;
 * after a sort that moves keys, the caret stays on the key it was on.
 */
class JsonSortArbAndCaretTest : PlatformBaseTest() {

    private fun sort(file: JsonFile) = WriteCommandAction.runWriteCommandAction(project) {
        SortI18nKeysAction().sort(file, project)
    }

    private fun keys(file: JsonFile) = (file.topLevelValue as JsonObject).propertyList.map { it.name }

    @Test
    fun anArbCatalogueKeepsEachMetadataBlockAfterItsKey() {
        val file = myFixture.configureByText(
            "app_en.arb",
            """
            {
              "zebra": "Zebra {count}",
              "@zebra": {"placeholders": {"count": {"type": "int"}}},
              "@@locale": "en",
              "@apple": {"description": "A fruit"},
              "apple": "Apple",
              "mango": "Mango"
            }
            """.trimIndent()
        ) as JsonFile
        sort(file)
        assertEquals(listOf("@@locale", "apple", "@apple", "mango", "zebra", "@zebra"), keys(file))
        val zebraBlock = (file.topLevelValue as JsonObject).findProperty("@zebra")!!.value!!.text
        assertEquals("""{"placeholders": {"count": {"type": "int"}}}""", zebraBlock)
    }

    @Test
    fun theGlobalEntriesOfAnArbStayFirstInTheirOrder() {
        val file = myFixture.configureByText(
            "app_fr.arb",
            """{"b": "B", "@@locale": "fr", "a": "A", "@@last_modified": "2026-11-14"}"""
        ) as JsonFile
        sort(file)
        assertEquals(listOf("@@locale", "@@last_modified", "a", "b"), keys(file))
    }

    @Test
    fun aSortedArbIsNotTouched() {
        val text = """{"@@locale": "en", "apple": "Apple", "@apple": {"description": "z then a", "z": 1, "a": 2}, "mango": "Mango"}"""
        val file = myFixture.configureByText("app_en.arb", text) as JsonFile
        sort(file)
        assertEquals(text, file.text)
    }

    @Test
    fun theCaretStaysOnItsKeyAfterSortI18nKeys() {
        myFixture.configureByText(
            "en.json",
            """
            {
              "zebra": "Zebra",
              "mango": "Ma<caret>ngo",
              "apple": "Apple"
            }
            """.trimIndent()
        )
        sort(myFixture.file as JsonFile)
        assertEquals(listOf("apple", "mango", "zebra"), keys(myFixture.file as JsonFile))
        assertCaretIn("mango", "Ma")
    }

    @Test
    fun theCaretStaysOnANestedKey() {
        myFixture.configureByText(
            "en.json",
            """{"b": {"y": "Why", "x": "E<caret>x"}, "a": "A"}"""
        )
        sort(myFixture.file as JsonFile)
        assertCaretIn("x", "E")
    }

    @Test
    fun theCaretStaysOnItsKeyWhenANewKeyIsInsertedWithResort() {
        myFixture.configureByText(
            "en.json",
            """
            {
              "b": "B",
              "c": "C<caret>",
              "a": "A"
            }
            """.trimIndent()
        )
        val file = myFixture.file as JsonFile
        myFixture.runWithConfig(Config(sortKeysAlphabetically = true)) {
            WriteCommandAction.runWriteCommandAction(project) {
                JsonLocalization().contentGenerator().generateTranslationEntry(file.topLevelValue as JsonObject, "aa", "\"AA\"")
            }
        }
        assertEquals(listOf("a", "aa", "b", "c"), keys(file))
        assertCaretIn("c", "C")
    }

    /** The caret sits in the value of [key], just after [before]. */
    private fun assertCaretIn(key: String, before: String) {
        val offset = myFixture.editor.caretModel.offset
        val property = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset - 1), JsonProperty::class.java)
        assertEquals(key, property?.name)
        val value = property!!.value!!
        val inValue = offset - value.textRange.startOffset - 1
        assertEquals(before, value.text.substring(1, 1 + inValue))
    }
}
