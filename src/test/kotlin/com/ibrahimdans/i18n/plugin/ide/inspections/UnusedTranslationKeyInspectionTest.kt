package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// In the test environment no JS/code references are configured, so ReferencesSearch returns null
// for all properties → every leaf string property is flagged as unused. This is the expected
// baseline for verifying filter logic (object vs. leaf, scalar vs. mapping).
class UnusedTranslationKeyInspectionTest : PlatformBaseTest() {

    private companion object {
        const val UNUSED_MSG = "Translation key is never used in code"
    }

    private fun unusedWarnings(content: String, fileName: String = "en.json"): List<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureByText(fileName, content)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == UNUSED_MSG }
    }

    private fun unusedWarningsFromFile(content: String, relativePath: String): List<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject(relativePath, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == UNUSED_MSG }
    }

    // JSON — leaf strings are flagged

    @Test
    fun testJsonLeafStringFlagged() {
        val warnings = unusedWarnings("""{"greeting": "Hello!"}""")
        assertTrue(warnings.isNotEmpty())
    }

    // JSON — object-valued properties are skipped (only leaves are leaf strings)

    @Test
    fun testJsonObjectValueNotFlagged() {
        // "root" → value is JsonObject → skipped; "key" → value is string → flagged
        val warnings = unusedWarnings("""{"root": {"key": "value"}}""")
        assertEquals(1, warnings.size)
    }

    @Test
    fun testJsonMultipleLeafsFlagged() {
        val warnings = unusedWarnings("""{"a": "one", "b": "two", "c": "three"}""")
        assertEquals(3, warnings.size)
    }

    // YAML — scalar values are flagged

    @Test
    fun testYamlScalarFlagged() {
        val warnings = unusedWarningsFromFile("greeting: Hello!", "en.yaml")
        assertTrue(warnings.isNotEmpty())
    }

    // YAML — mapping-valued keys are skipped (only leaf scalars are flagged)

    @Test
    fun testYamlNestedMappingNotFlagged() {
        val content = """
            nested:
              key: value
        """.trimIndent()
        val warnings = unusedWarningsFromFile(content, "en.yaml")
        // "nested" → value is YAMLMapping → skipped; "key" → value is YAMLScalar → flagged
        assertEquals(1, warnings.size)
    }
}
