package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the `sortKeysAlphabetically` setting: when enabled, the JSON insertion path
 * re-sorts the whole file after each key creation.
 */
class SortKeysOnInsertTest : PlatformBaseTest() {

    private fun insertKey(content: String, key: String, value: String, sortEnabled: Boolean): List<String> =
        insertKey(content, key, value, Config(sortKeysAlphabetically = sortEnabled))

    private fun insertKey(content: String, key: String, value: String, config: Config): List<String> {
        val file = myFixture.configureByText("en.json", content) as JsonFile
        myFixture.runWithConfig(config) {
            WriteCommandAction.runWriteCommandAction(project) {
                JsonLocalization().contentGenerator()
                    .generateTranslationEntry(file.topLevelValue as JsonObject, key, "\"$value\"")
            }
        }
        return (file.topLevelValue as JsonObject).propertyList.map { it.name }
    }

    @Test
    fun testInsertReSortsWholeFileWhenEnabled() {
        // Unsorted file; inserting "cherry" must produce a fully sorted file
        val keys = insertKey("""{"banana": "1", "apple": "2"}""", "cherry", "3", sortEnabled = true)
        assertEquals(listOf("apple", "banana", "cherry"), keys)
    }

    @Test
    fun testInsertDoesNotReSortWhenDisabled() {
        // Default behaviour: the new key is appended, the rest keeps its original (unsorted) order
        val keys = insertKey("""{"banana": "1", "apple": "2"}""", "cherry", "3", sortEnabled = false)
        assertEquals(listOf("banana", "apple", "cherry"), keys)
    }

    @Test
    fun testInsertIntoEmptyObjectWhenEnabled() {
        val keys = insertKey("""{}""", "only", "1", sortEnabled = true)
        assertEquals(listOf("only"), keys)
    }

    @Test
    fun testSortedInsertionIgnoresCaseByDefault() {
        val keys = insertKey("""{"apple": "1", "Zebra": "2"}""", "Banana", "3", Config(extractSorted = true))
        assertEquals(listOf("apple", "Banana", "Zebra"), keys)
    }

    @Test
    fun testSortedInsertionFollowsCharacterCodeOrder() {
        val config = Config(extractSorted = true, keyOrder = KeyOrder.CHARACTER_CODE)
        val keys = insertKey("""{"Banana": "1", "Zebra": "2", "cherry": "3"}""", "apple", "4", config)
        assertEquals(listOf("Banana", "Zebra", "apple", "cherry"), keys)
    }

    /**
     * In a file already sorted, the key lands where the full sort would put it: the two never
     * disagree. They used to in the case-insensitive order, the insertion comparing character codes.
     */
    @Test
    fun testSortedInsertionAgreesWithTheFullSortInEitherOrder() {
        KeyOrder.entries.forEach { order ->
            val file = listOf("Banana", "Zebra", "cherry", "_meta").sortedWith(order.comparator)
                .joinToString(", ", "{", "}") { "\"$it\": \"x\"" }
            val inserted = insertKey(file, "apple", "4", Config(extractSorted = true, keyOrder = order))
            val resorted = insertKey(file, "apple", "4", Config(extractSorted = true, sortKeysAlphabetically = true, keyOrder = order))
            assertEquals(resorted, inserted, "order $order")
        }
    }
}
