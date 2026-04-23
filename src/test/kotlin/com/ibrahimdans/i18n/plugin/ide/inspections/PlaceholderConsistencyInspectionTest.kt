package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.PlaceholderConsistencyInspection
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholderConsistencyInspectionTest : PlatformBaseTest() {

    private fun placeholderWarnings(
        enContent: String,
        translatedContent: String,
        translatedFileName: String = "fr.json"
    ): List<String> {
        myFixture.enableInspections(PlaceholderConsistencyInspection::class.java)
        myFixture.addFileToProject("en.json", enContent)
        val file = myFixture.addFileToProject(translatedFileName, translatedContent)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("Placeholder") }
    }

    // JSON — valid cases

    @Test
    fun testSamePlaceholdersNoWarning() {
        val warnings = placeholderWarnings(
            """{"greeting": "Hello {name}!"}""",
            """{"greeting": "Bonjour {name}!"}"""
        )
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun testNoPlaceholdersNoWarning() {
        val warnings = placeholderWarnings(
            """{"greeting": "Hello!"}""",
            """{"greeting": "Bonjour!"}"""
        )
        assertTrue(warnings.isEmpty())
    }

    // If the translated value has NO placeholders at all, the inspection skips it
    // (by design: the translator may have intentionally omitted them).
    @Test
    fun testTranslatedValueWithNoPlaceholdersSkipped() {
        val warnings = placeholderWarnings(
            """{"greeting": "Hello {name}!"}""",
            """{"greeting": "Bonjour!"}"""
        )
        assertTrue(warnings.isEmpty())
    }

    // JSON — invalid cases

    @Test
    fun testMissingPlaceholderInTranslation() {
        val warnings = placeholderWarnings(
            """{"msg": "Hello {name}, you have {count} messages!"}""",
            """{"msg": "Bonjour {name}!"}"""
        )
        assertTrue(warnings.any { it.contains("{count}") && it.contains("missing") })
    }

    @Test
    fun testUnbalancedOpenBrace() {
        val warnings = placeholderWarnings(
            """{"greeting": "Hello {name}!"}""",
            """{"greeting": "Bonjour {prenom"}"""
        )
        assertTrue(warnings.any { it.contains("unbalanced braces") })
    }

    // No reference file → siblingFileTranslations returns emptyMap → no warning
    @Test
    fun testNoReferenceFileNoWarning() {
        myFixture.enableInspections(PlaceholderConsistencyInspection::class.java)
        val file = myFixture.addFileToProject("subdir/fr.json", """{"greeting": "Bonjour {name}!"}""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val warnings = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("Placeholder") }
        assertTrue(warnings.isEmpty())
    }

    // YAML — valid case

    @Test
    fun testYamlSamePlaceholdersNoWarning() {
        myFixture.enableInspections(PlaceholderConsistencyInspection::class.java)
        myFixture.addFileToProject("en.yaml", "greeting: \"Hello {name}!\"")
        val file = myFixture.addFileToProject("fr.yaml", "greeting: \"Bonjour {name}!\"")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val warnings = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("Placeholder") }
        assertTrue(warnings.isEmpty())
    }

    // YAML — invalid case

    @Test
    fun testYamlMissingPlaceholder() {
        myFixture.enableInspections(PlaceholderConsistencyInspection::class.java)
        myFixture.addFileToProject("en.yaml", "msg: \"Hello {name}, you have {count} messages!\"")
        val file = myFixture.addFileToProject("fr.yaml", "msg: \"Bonjour {name}!\"")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val warnings = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { it.startsWith("Placeholder") }
        assertTrue(warnings.any { it.contains("{count}") && it.contains("missing") })
    }
}
