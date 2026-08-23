package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.IcuFormatInspection
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IcuFormatInspectionTest : PlatformBaseTest() {

    private fun icuWarnings(content: String, fileName: String = "en.json"): List<String> {
        myFixture.enableInspections(IcuFormatInspection::class.java)
        myFixture.configureByText(fileName, content)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it in ICU_MESSAGES }
    }

    private companion object {
        /**
         * What the inspection can say, read from the bundle rather than retyped here.
         * Highlighting returns every description in the file, so the list is also what
         * separates this inspection's warnings from the rest.
         */
        val MISSING_OTHER_PLURAL: String get() = PluginBundle.getMessage("inspection.icu.missing.other", "plural")
        val MISSING_OTHER_SELECT: String get() = PluginBundle.getMessage("inspection.icu.missing.other", "select")
        val PLURAL_FORMS: String get() = PluginBundle.getMessage("inspection.icu.plural.forms")
        val UNBALANCED: String get() = PluginBundle.getMessage("inspection.icu.unbalanced")

        val ICU_MESSAGES: Set<String>
            get() = setOf(MISSING_OTHER_PLURAL, MISSING_OTHER_SELECT, PLURAL_FORMS, UNBALANCED)
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
        assertTrue(warnings.contains(MISSING_OTHER_PLURAL) || warnings.contains(MISSING_OTHER_SELECT))
    }

    @Test
    fun testPluralMissingOneAndZeroForm() {
        val warnings = icuWarnings("""{"count": "{count, plural, other {# items}}"}""")
        assertTrue(warnings.contains(PLURAL_FORMS))
    }

    @Test
    fun testSelectMissingOtherForm() {
        val warnings = icuWarnings("""{"gender": "{gender, select, male {He} female {She}}"}""")
        assertTrue(warnings.contains(MISSING_OTHER_PLURAL) || warnings.contains(MISSING_OTHER_SELECT))
    }

    @Test
    fun testUnbalancedOpenBrace() {
        val warnings = icuWarnings("""{"msg": "unclosed { brace"}""")
        assertTrue(warnings.contains(UNBALANCED))
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
        assertTrue(warnings.contains(MISSING_OTHER_PLURAL) || warnings.contains(MISSING_OTHER_SELECT))
    }

    @Test
    fun testUnbalancedBraceYaml() {
        val warnings = icuWarnings(
            "msg: \"unclosed { brace\"",
            "en.yaml"
        )
        assertTrue(warnings.contains(UNBALANCED))
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
