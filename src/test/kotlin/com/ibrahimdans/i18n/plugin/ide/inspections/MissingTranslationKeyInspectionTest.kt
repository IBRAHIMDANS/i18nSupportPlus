package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissingTranslationKeyInspectionTest : PlatformBaseTest() {

    private fun missingKeyWarnings(path: String, content: String): List<String> {
        myFixture.enableInspections(MissingTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .mapNotNull { it.description }
            .filter { it.startsWith("Key ") }
    }

    // JSON

    @Test
    fun aKeyMissingFromALocaleIsReported() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": {"name": "Name", "age": "Age"}, "title": "Title"}""")
        val warnings = missingKeyWarnings("locales/fr/common.json", """{"user": {"name": "Nom"}}""")
        Assertions.assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.any { it.contains("'user.age'") && it.contains("'en'") }, "$warnings")
        assertTrue(warnings.any { it.contains("'title'") }, "$warnings")
    }

    /** No locale declared: the default `en` designates the `en-US` files, and the message names them. */
    @Test
    fun aRegionalReferenceLocaleIsFoundFromItsLanguage() {
        myFixture.addFileToProject("locales/en-US/common.json", """{"title": "Title", "bye": "Bye"}""")
        val warnings = missingKeyWarnings("locales/fr-FR/common.json", """{"title": "Titre"}""")
        assertTrue(warnings.any { it.contains("'bye'") && it.contains("'en-US'") }, "$warnings")
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.addFileToProject("locales/fr/common.json", """{"onlyInFrench": "Seulement"}""")
        val warnings = missingKeyWarnings("locales/en/common.json", """{"title": "Title"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aCompleteFileHasNoProblem() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}, "title": "Title"}""")
        val warnings = missingKeyWarnings("locales/fr/common.json", """{"title": "Titre", "user": {"name": "Nom"}, "extra": "x"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun anotherNamespaceIsNotComparedAgainst() {
        myFixture.addFileToProject("locales/en/auth.json", """{"login": "Log in"}""")
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        val warnings = missingKeyWarnings("locales/fr/common.json", """{"title": "Titre"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    /** `ja` has the `other` category only: its `item_other` is the whole plural `en` spells in two forms. */
    @Test
    fun pluralFormsAreComparedAsAGroup() {
        myFixture.addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        val warnings = missingKeyWarnings("locales/ja/common.json", """{"item_other": "{{count}} 個"}""")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aMissingPluralGroupIsReportedOnceByItsOtherForm() {
        myFixture.addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        val warnings = missingKeyWarnings("locales/fr/common.json", """{"title": "Titre"}""")
        Assertions.assertEquals(listOf("item_other"), warnings.map { it.substringAfter("'").substringBefore("'") })
    }

    // Quick fix

    @Test
    fun theQuickFixAddsTheKeyWithAnEmptyValue() {
        myFixture.addFileToProject("locales/en/common.json", """{"user": {"name": "Name", "age": "Age"}}""")
        missingKeyWarnings("locales/fr/common.json", """{"user": {"name": "Nom"}}""")
        val fix = myFixture.getAllQuickFixes()
            .firstOrNull { it.text == PluginBundle.getMessage("inspection.missing.key.fix.name", "user.age") }
        assertTrue(fix != null, "quick fix should be offered")
        myFixture.launchAction(fix!!)

        val actual = myFixture.file.text.filterNot { it.isWhitespace() }
        Assertions.assertEquals("""{"user":{"name":"Nom","age":""}}""", actual)
        val remaining = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Key ") }
        assertTrue(remaining.isEmpty(), "the added key must no longer be reported: $remaining")
    }

    @Test
    fun theQuickFixCreatesTheMissingParents() {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title", "user": {"name": "Name"}}""")
        missingKeyWarnings("locales/fr/common.json", """{"title": "Titre"}""")
        val fix = myFixture.getAllQuickFixes()
            .firstOrNull { it.text == PluginBundle.getMessage("inspection.missing.key.fix.name", "user.name") }
        assertTrue(fix != null, "quick fix should be offered")
        myFixture.launchAction(fix!!)

        val actual = myFixture.file.text.filterNot { it.isWhitespace() }
        Assertions.assertEquals("""{"title":"Titre","user":{"name":""}}""", actual)
    }

    // YAML

    @Test
    fun aKeyMissingFromAYamlLocaleIsReported() {
        myFixture.addFileToProject("locales/en/common.yaml", "user:\n  name: Name\n  age: Age\n")
        val warnings = missingKeyWarnings("locales/fr/common.yaml", "user:\n  name: Nom\n")
        Assertions.assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().contains("'user.age'"), "$warnings")
    }

    @Test
    fun aCompleteYamlLocaleHasNoProblem() {
        myFixture.addFileToProject("locales/en/common.yaml", "user:\n  name: Name\n")
        val warnings = missingKeyWarnings("locales/fr/common.yaml", "user:\n  name: Nom\n")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    // YAML with several documents: only the first is read, as key resolution reads it.

    /** A key of the reference's second document resolves nowhere: no locale is asked for it. */
    @Test
    fun aKeyInALaterReferenceDocumentIsNotAskedFor() {
        myFixture.addFileToProject("locales/en/common.yaml", "title: Title\n---\nextra: Extra\n")
        val warnings = missingKeyWarnings("locales/fr/common.yaml", "title: Titre\n")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    /** A key written in a later document of the locale is not found by the code: still missing. */
    @Test
    fun aKeyInALaterLocaleDocumentStillCountsAsMissing() {
        myFixture.addFileToProject("locales/en/common.yaml", "title: Title\nbye: Bye\n")
        val warnings = missingKeyWarnings("locales/fr/common.yaml", "title: Titre\n---\nbye: Au revoir\n")
        Assertions.assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().contains("'bye'"), "$warnings")
    }
}
