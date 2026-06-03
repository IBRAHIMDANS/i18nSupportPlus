package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortI18nKeysActionTest : PlatformBaseTest() {

    private fun sortedKeys(content: String): List<String> {
        val file = myFixture.configureByText("en.json", content) as JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            SortI18nKeysAction().sort(file, project)
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

    @Test
    fun testSingleKeyUnchanged() {
        val keys = sortedKeys("""{"only": "1"}""")
        assertEquals(listOf("only"), keys)
    }
}
