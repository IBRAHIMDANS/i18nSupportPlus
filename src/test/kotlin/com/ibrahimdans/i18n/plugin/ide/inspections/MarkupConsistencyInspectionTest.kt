package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.MarkupConsistencyInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkupConsistencyInspectionTest : PlatformBaseTest() {

    /** The tag warnings on [translated] at `locales/fr/common.<ext>`, compared with [reference] in `en`. */
    private fun tagWarnings(reference: String, translated: String, ext: String = "json"): List<String> {
        myFixture.enableInspections(MarkupConsistencyInspection::class.java)
        myFixture.addFileToProject("locales/en/common.$ext", reference)
        val file = myFixture.addFileToProject("locales/fr/common.$ext", translated)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Tag ") }
    }

    @Test
    fun aMissingClosingTagIsReported() {
        val warnings = tagWarnings("""{"terms": "See <1>the terms</1>"}""", """{"terms": "Voir <1>les CGU"}""")
        assertEquals(listOf("Tag '</1>' of the reference locale is missing here"), warnings)
    }

    @Test
    fun anExtraTagIsReported() {
        val warnings = tagWarnings("""{"save": "Save"}""", """{"save": "<b>Enregistrer</b>"}""")
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.all { it.endsWith("is not in the reference locale") }, "$warnings")
    }

    @Test
    fun reorderedTagsAreNotReported() {
        val warnings = tagWarnings(
            """{"msg": "<0>Hello</0> <1>world</1>"}""",
            """{"msg": "<1>monde</1> <0>bonjour</0>"}"""
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun selfClosingTagsAndAttributesAreNormalised() {
        val warnings = tagWarnings(
            """{"msg": "Line<br/>next <a href='/a'>link</a>"}""",
            """{"msg": "Ligne<br />suite <a href='/b'>lien</a>"}"""
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aLessThanSignIsNoTag() {
        val warnings = tagWarnings("""{"cmp": "a < b"}""", """{"cmp": "a < b et c > d"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.addFileToProject("locales/fr/common.json", """{"save": "<b>Enregistrer</b>"}""")
        myFixture.enableInspections(MarkupConsistencyInspection::class.java)
        val file = myFixture.addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val warnings = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Tag ") }
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun yamlValuesAreCompared() {
        val warnings = tagWarnings("terms: See <1>the terms</1>\n", "terms: Voir <1>les CGU\n", "yaml")
        assertEquals(listOf("Tag '</1>' of the reference locale is missing here"), warnings)
    }
}
