package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Baseline: in the test environment no IDE-level references are configured for
// ReferencesSearch, so pure ReferencesSearch returns null for all properties.
// The inspection also checks the plugin's own TranslationToCodeReference (nameElement.references),
// which DOES work in tests when a code file is present — see testJsonKeyWithCodeReferenceNotFlagged.
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
    // JSON — a key that has a code reference is NOT flagged

    @Test
    fun testJsonKeyWithCodeReferenceNotFlagged() {
        // Add a PHP code file that calls t('test:greeting'), establishing a reference to the "greeting" key
        myFixture.addFileToProject("src/App.php", "<?php echo t('test:greeting');")
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        val jsonFile = myFixture.addFileToProject(
            "assets/test.json",
            """{"greeting": "Hello!", "unused": "World"}"""
        )
        myFixture.configureFromExistingVirtualFile(jsonFile.virtualFile)
        val warnings = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == UNUSED_MSG }
        // "greeting" is referenced in PHP code → must NOT be flagged
        // "unused" has no code reference → must be flagged
        assertEquals(1, warnings.size)
    }
}
