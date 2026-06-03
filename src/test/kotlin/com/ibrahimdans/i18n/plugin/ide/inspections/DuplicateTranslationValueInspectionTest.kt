package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.DuplicateTranslationValueInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DuplicateTranslationValueInspectionTest : PlatformBaseTest() {

    private companion object {
        const val DUP_MSG = "Translation value is duplicated by another key in this file"
    }

    private fun dupWarnings(content: String, fileName: String = "en.json"): List<String> {
        myFixture.enableInspections(DuplicateTranslationValueInspection::class.java)
        myFixture.configureByText(fileName, content)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == DUP_MSG }
    }

    private fun dupWarningsFromFile(content: String, relativePath: String): List<String> {
        myFixture.enableInspections(DuplicateTranslationValueInspection::class.java)
        val file = myFixture.addFileToProject(relativePath, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it == DUP_MSG }
    }

    // JSON — two keys with the same value: both flagged

    @Test
    fun testJsonDuplicateFlaggedOnBoth() {
        val warnings = dupWarnings("""{"a": "Hello", "b": "Hello"}""")
        assertEquals(2, warnings.size)
    }

    // JSON — three identical values: all flagged

    @Test
    fun testJsonTripleDuplicate() {
        val warnings = dupWarnings("""{"a": "Hi", "b": "Hi", "c": "Hi"}""")
        assertEquals(3, warnings.size)
    }

    // JSON — distinct values: none flagged

    @Test
    fun testJsonDistinctNotFlagged() {
        val warnings = dupWarnings("""{"a": "one", "b": "two", "c": "three"}""")
        assertEquals(0, warnings.size)
    }

    // JSON — blank values are ignored (covered by the empty-value inspection)

    @Test
    fun testJsonBlankValuesIgnored() {
        val warnings = dupWarnings("""{"a": "", "b": "", "c": "real"}""")
        assertEquals(0, warnings.size)
    }

    // JSON — duplicates across nesting levels are detected (file-scoped)

    @Test
    fun testJsonNestedDuplicate() {
        val warnings = dupWarnings("""{"top": "Same", "group": {"inner": "Same"}}""")
        assertEquals(2, warnings.size)
    }

    // YAML — two keys with the same scalar value: both flagged

    @Test
    fun testYamlDuplicateFlagged() {
        val content = """
            a: Hello
            b: Hello
        """.trimIndent()
        val warnings = dupWarningsFromFile(content, "en.yaml")
        assertEquals(2, warnings.size)
    }

    // YAML — distinct scalars: none flagged

    @Test
    fun testYamlDistinctNotFlagged() {
        val content = """
            a: one
            b: two
        """.trimIndent()
        val warnings = dupWarningsFromFile(content, "en.yaml")
        assertEquals(0, warnings.size)
    }
}
