package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortI18nKeysActionTest : PlatformBaseTest() {

    private fun sortedKeys(content: String, order: KeyOrder = KeyOrder.IGNORE_CASE): List<String> {
        val file = myFixture.configureByText("en.json", content) as JsonFile
        myFixture.runWithConfig(Config(keyOrder = order)) {
            WriteCommandAction.runWriteCommandAction(project) {
                SortI18nKeysAction().sort(file, project)
            }
        }
        val obj = file.topLevelValue as JsonObject
        return obj.propertyList.map { it.name }
    }

    private fun nestedKeys(content: String, parentKey: String): List<String> {
        val file = myFixture.configureByText("en.json", content) as JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            SortI18nKeysAction().sort(file, project)
        }
        val obj = file.topLevelValue as JsonObject
        val nested = obj.findProperty(parentKey)!!.value as JsonObject
        return nested.propertyList.map { it.name }
    }

    @Test
    fun testTopLevelSorted() {
        val keys = sortedKeys("""{"banana": "1", "apple": "2", "cherry": "3"}""")
        assertEquals(listOf("apple", "banana", "cherry"), keys)
    }

    @Test
    fun testCaseInsensitiveOrder() {
        val keys = sortedKeys("""{"Zebra": "1", "apple": "2", "Banana": "3"}""")
        assertEquals(listOf("apple", "Banana", "Zebra"), keys)
    }

    @Test
    fun testCharacterCodeOrderMatchesTheIde() {
        val keys = sortedKeys("""{"apple": "1", "Zebra": "2", "banana": "3", "Banana": "4"}""", KeyOrder.CHARACTER_CODE)
        assertEquals(listOf("Banana", "Zebra", "apple", "banana"), keys)
    }

    @Test
    fun testCharacterCodeOrderAppliesToNestedObjects() {
        val file = myFixture.configureByText("en.json", """{"root": {"b": "1", "A": "2", "a": "3"}}""") as JsonFile
        myFixture.runWithConfig(Config(keyOrder = KeyOrder.CHARACTER_CODE)) {
            WriteCommandAction.runWriteCommandAction(project) {
                SortI18nKeysAction().sort(file, project)
            }
        }
        val nested = (file.topLevelValue as JsonObject).findProperty("root")!!.value as JsonObject
        assertEquals(listOf("A", "a", "b"), nested.propertyList.map { it.name })
    }

    @Test
    fun testAlreadySortedUnchanged() {
        val keys = sortedKeys("""{"a": "1", "b": "2", "c": "3"}""")
        assertEquals(listOf("a", "b", "c"), keys)
    }

    @Test
    fun testNestedObjectSorted() {
        val content = """{"root": {"y": "1", "x": "2", "z": "3"}}"""
        assertEquals(listOf("x", "y", "z"), nestedKeys(content, "root"))
    }

    @Test
    fun testValuesPreservedAfterSort() {
        val file = myFixture.configureByText("en.json", """{"b": "beta", "a": "alpha"}""") as JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            SortI18nKeysAction().sort(file, project)
        }
        val obj = file.topLevelValue as JsonObject
        // Order changed, but each key keeps its own value
        assertEquals(listOf("a", "b"), obj.propertyList.map { it.name })
        assertEquals("alpha", (obj.findProperty("a")!!.value as com.intellij.json.psi.JsonStringLiteral).value)
        assertEquals("beta", (obj.findProperty("b")!!.value as com.intellij.json.psi.JsonStringLiteral).value)
    }

    /**
     * Only the properties that move change: indent, aligned values and one-line objects stay as
     * written. The whole file used to be reformatted with the IDE's JSON style (2 spaces).
     */
    @Test
    fun testSortMovesPropertiesWithoutReformattingTheFile() {
        val file = myFixture.configureByText(
            "en.json",
            "{\n    \"b\": \"B\",\n    \"a\":   \"A\",\n    \"c\": {\n        \"y\": \"Y\",\n        \"x\": \"X\"\n    },\n" +
                "    \"d\": { \"k\": \"K\" },\n    \"e\":\t\"E\"\n}"
        ) as JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            SortI18nKeysAction().sort(file, project)
        }
        myFixture.checkResult(
            "{\n    \"a\":   \"A\",\n    \"b\": \"B\",\n    \"c\": {\n        \"x\": \"X\",\n        \"y\": \"Y\"\n    },\n" +
                "    \"d\": { \"k\": \"K\" },\n    \"e\":\t\"E\"\n}"
        )
    }

    @Test
    fun testSortOfAnAlreadySortedFileChangesNothing() {
        val text = "{\n\t\"a\" : \"A\",\n\t\"b\": {\"x\":\"X\",   \"y\": \"Y\"}\n}"
        val file = myFixture.configureByText("en.json", text) as JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            SortI18nKeysAction().sort(file, project)
        }
        myFixture.checkResult(text)
    }

    @Test
    fun testSingleKeyUnchanged() {
        val keys = sortedKeys("""{"only": "1"}""")
        assertEquals(listOf("only"), keys)
    }
}
