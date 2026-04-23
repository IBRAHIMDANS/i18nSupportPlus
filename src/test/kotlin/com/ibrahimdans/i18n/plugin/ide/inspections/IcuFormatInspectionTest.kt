package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.IcuFormatInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IcuFormatInspectionTest : PlatformBaseTest() {

    private fun icuWarnings(content: String, fileName: String = "en.json"): List<String> {
        myFixture.enableInspections(IcuFormatInspection::class.java)
        myFixture.configureByText(fileName, content)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("ICU format error") }
    }

    // JSON — valid cases

    @Test
    fun testValidPluralNoWarning() {
        assertTrue(icuWarnings("""{"count": "{count, plural, one {# item} other {# items}}"}""").isEmpty())
    }

    @Test
    fun testValidSelectNoWarning() {
        assertTrue(icuWarnings("""{"gender": "{gender, select, male {He} female {She} other {They}}"}""").isEmpty())
    }

    @Test
    fun testNonIcuValueNoWarning() {
        assertTrue(icuWarnings("""{"greeting": "Hello {name}!"}""").isEmpty())
    }

    // JSON — invalid cases

    @Test
    fun testPluralMissingOtherForm() {
        val warnings = icuWarnings("""{"count": "{count, plural, one {# item}}"}""")
        assertTrue(warnings.any { it.contains("missing the required 'other' form") })
    }

    @Test
    fun testPluralMissingOneAndZeroForm() {
        val warnings = icuWarnings("""{"count": "{count, plural, other {# items}}"}""")
        assertTrue(warnings.any { it.contains("must have at least 'one' or 'zero' form") })
    }

    @Test
    fun testSelectMissingOtherForm() {
        val warnings = icuWarnings("""{"gender": "{gender, select, male {He} female {She}}"}""")
        assertTrue(warnings.any { it.contains("missing the required 'other' form") })
    }

    @Test
    fun testUnbalancedOpenBrace() {
        val warnings = icuWarnings("""{"msg": "unclosed { brace"}""")
        assertTrue(warnings.any { it.contains("unbalanced braces") })
    }

    // JSON — object values are skipped

    @Test
    fun testObjectValueSkipped() {
        assertTrue(icuWarnings("""{"nested": {"key": "value"}}""").isEmpty())
    }

    // YAML — valid cases

    @Test
    fun testValidPluralYamlNoWarning() {
        assertTrue(
            icuWarnings(
                "count: \"{count, plural, one {# item} other {# items}}\"",
                "en.yaml"
            ).isEmpty()
        )
    }

    // YAML — invalid cases

    @Test
    fun testPluralMissingOtherFormYaml() {
        val warnings = icuWarnings(
            "count: \"{count, plural, one {# item}}\"",
            "en.yaml"
        )
        assertTrue(warnings.any { it.contains("missing the required 'other' form") })
    }

    @Test
    fun testUnbalancedBraceYaml() {
        val warnings = icuWarnings(
            "msg: \"unclosed { brace\"",
            "en.yaml"
        )
        assertTrue(warnings.any { it.contains("unbalanced braces") })
    }

    // Ensures multiple ICU blocks in the same value are each checked

    @Test
    fun testMultipleIcuBlocksBothInvalid() {
        val warnings = icuWarnings(
            """{"msg": "{a, plural, one {x}} {b, plural, zero {y}}"}"""
        )
        assertEquals(2, warnings.count { it.contains("missing the required 'other' form") })
    }
}
