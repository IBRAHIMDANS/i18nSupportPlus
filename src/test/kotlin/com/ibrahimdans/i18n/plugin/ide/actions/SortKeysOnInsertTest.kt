package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
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

    private fun insertKey(content: String, key: String, value: String, sortEnabled: Boolean): List<String> {
        val file = myFixture.configureByText("en.json", content) as JsonFile
        myFixture.runWithConfig(Config(sortKeysAlphabetically = sortEnabled)) {
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
}
