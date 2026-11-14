package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * What the re-sort costs in a large JSON file (`sortKeysAlphabetically`). A new key used to be
 * appended at the end, then the re-sort replaced every property after its place with a copy, one
 * tree change each: about a thousand in a 2,000-key file, 24 s in this fixture, run in the write
 * command the IDE then reported as slow. Sorting a reversed file took 43 s the same way. The key
 * now lands at its place, a sorted file is left alone, and a sort writes the file in one change.
 */
class JsonResortCostTest : PlatformBaseTest() {

    private val size = 2000

    private fun keyAt(i: Int) = "key%05d".format(i * 2)

    private fun fileOf(indices: List<Int>): String =
        indices.joinToString(",\n  ", "{\n  ", "\n}\n") { "\"${keyAt(it)}\": \"value $it\"" }

    private fun sortedFile() = fileOf((0 until size).toList())

    @Test
    fun aNewKeyInASortedFileLeavesTheOtherPropertiesInPlace() {
        val file = myFixture.configureByText("en.json", sortedFile()) as JsonFile
        val before: List<JsonProperty> = (file.topLevelValue as JsonObject).propertyList
        // Between key01000 and key01002: half the file follows it.
        val middle = "key%05d".format(size + 1)

        myFixture.runWithConfig(Config(sortKeysAlphabetically = true)) {
            WriteCommandAction.runWriteCommandAction(project) {
                JsonLocalization().contentGenerator()
                    .generateTranslationEntry(file.topLevelValue as JsonObject, middle, "\"new\"")
            }
        }

        val after = (file.topLevelValue as JsonObject).propertyList
        assertEquals(size + 1, after.size)
        assertEquals(after.map { it.name }.sorted(), after.map { it.name })
        assertEquals(middle, after[size / 2 + 1].name)
        val kept = after.toSet()
        assertEquals(0, before.count { it !in kept }, "properties rewritten by the re-sort")
    }

    @Test
    fun reSortingAnAlreadySortedFileRewritesNothing() {
        val file = myFixture.configureByText("en.json", sortedFile()) as JsonFile
        val root = file.topLevelValue
        val text = file.text

        WriteCommandAction.runWriteCommandAction(project) { JsonKeySorter.sort(file, project) }

        assertEquals(text, file.text)
        assertSame(root, file.topLevelValue, "an already sorted file must not be rewritten")
    }

    @Test
    fun aReversedFileIsSortedWithItsLayoutKept() {
        val file = myFixture.configureByText("en.json", fileOf((0 until size).reversed().toList())) as JsonFile

        WriteCommandAction.runWriteCommandAction(project) { JsonKeySorter.sort(file, project) }

        assertEquals(sortedFile(), file.text)
    }

    @Test
    fun nestedObjectsAreSortedInTheSameChange() {
        val file = myFixture.configureByText(
            "en.json",
            "{\n  \"b\": {\n    \"y\": \"1\",\n    \"x\": \"2\"\n  },\n  \"a\": \"3\"\n}\n",
        ) as JsonFile

        WriteCommandAction.runWriteCommandAction(project) { JsonKeySorter.sort(file, project) }

        assertEquals("{\n  \"a\": \"3\",\n  \"b\": {\n    \"x\": \"2\",\n    \"y\": \"1\"\n  }\n}\n", file.text)
    }

    @Test
    fun aSortedTopLevelWithAnUnsortedNestedObjectIsSorted() {
        val file = myFixture.configureByText("en.json", """{"a": {"z": "1", "m": "2"}, "b": "3"}""") as JsonFile

        WriteCommandAction.runWriteCommandAction(project) { JsonKeySorter.sort(file, project) }

        assertEquals("""{"a": {"m": "2", "z": "1"}, "b": "3"}""", file.text)
    }
}
