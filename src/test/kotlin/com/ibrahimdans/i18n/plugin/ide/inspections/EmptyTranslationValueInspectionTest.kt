package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.EmptyTranslationValueInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmptyTranslationValueInspectionTest : PlatformBaseTest() {

    private companion object {
        const val EMPTY_MSG = "Translation value is empty"
    }

    private fun emptyWarnings(content: String, fileName: String = "en.json"): List<String> {
        myFixture.enableInspections(EmptyTranslationValueInspection::class.java)
        myFixture.configureByText(fileName, content)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == EMPTY_MSG }
    }

    private fun emptyWarningsFromFile(content: String, relativePath: String): List<String> {
        myFixture.enableInspections(EmptyTranslationValueInspection::class.java)
        val file = myFixture.addFileToProject(relativePath, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == EMPTY_MSG }
    }

    // JSON — empty string value is flagged

    @Test
    fun testJsonEmptyStringFlagged() {
        val warnings = emptyWarnings("""{"greeting": ""}""")
        assertEquals(1, warnings.size)
    }

    // JSON — whitespace-only value is flagged

    @Test
    fun testJsonBlankStringFlagged() {
        val warnings = emptyWarnings("""{"greeting": "   "}""")
        assertEquals(1, warnings.size)
    }

    // JSON — non-empty value is not flagged

    @Test
    fun testJsonNonEmptyNotFlagged() {
        val warnings = emptyWarnings("""{"greeting": "Hello!"}""")
        assertEquals(0, warnings.size)
    }

    // JSON — object-valued property is skipped (only the empty leaf is flagged)

    @Test
    fun testJsonObjectValueSkipped() {
        // "root" → object value → skipped; "key" → empty string → flagged
        val warnings = emptyWarnings("""{"root": {"key": ""}}""")
        assertEquals(1, warnings.size)
    }

    @Test
    fun testJsonMixedFlagsOnlyEmpty() {
        val warnings = emptyWarnings("""{"a": "one", "b": "", "c": "three", "d": ""}""")
        assertEquals(2, warnings.size)
    }

    // YAML — empty scalar value is flagged

    @Test
    fun testYamlEmptyScalarFlagged() {
        val warnings = emptyWarningsFromFile("greeting: \"\"", "en.yaml")
        assertEquals(1, warnings.size)
    }

    // YAML — non-empty scalar is not flagged

    @Test
    fun testYamlNonEmptyNotFlagged() {
        val warnings = emptyWarningsFromFile("greeting: Hello!", "en.yaml")
        assertEquals(0, warnings.size)
    }

    // YAML — mapping-valued key is skipped (only the empty leaf is flagged)

    @Test
    fun testYamlNestedMappingSkipped() {
        val content = """
            nested:
              key: ""
        """.trimIndent()
        val warnings = emptyWarningsFromFile(content, "en.yaml")
        assertEquals(1, warnings.size)
    }
}
