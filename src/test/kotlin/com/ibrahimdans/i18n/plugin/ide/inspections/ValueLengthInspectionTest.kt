package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.ValueLengthInspection
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A translation much longer than its reference is reported; short or comparable ones are not. */
class ValueLengthInspectionTest : PlatformBaseTest() {

    private fun lengthWarnings(path: String, content: String, inspection: ValueLengthInspection = ValueLengthInspection()): List<String> {
        myFixture.enableInspections(inspection)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .mapNotNull { it.description }
            .filter { it.startsWith("Translation is ") }
    }

    @Test
    fun aMuchLongerTranslationIsReported() {
        // 12 characters → 27.
        myFixture.addFileToProject("locales/en/common.json", """{"save": "Save changes"}""")
        val warnings = lengthWarnings("locales/fr/common.json", """{"save": "Enregistrer les changements"}""")
        Assertions.assertEquals(1, warnings.size, "$warnings")
        assertTrue(warnings.single().startsWith("Translation is 225%"), "$warnings")
    }

    @Test
    fun aComparableLengthIsNotReported() {
        // 10 characters → 14: 140 %.
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Your title"}""")
        assertTrue(lengthWarnings("locales/fr/common.json", """{"title": "Votre intitulé"}""").isEmpty())
    }

    @Test
    fun aShortReferenceIsNeverCompared() {
        myFixture.addFileToProject("locales/en/common.json", """{"ok": "Okay!"}""")
        assertTrue(lengthWarnings("locales/fr/common.json", """{"ok": "D'accord, c'est entendu"}""").isEmpty())
    }

    @Test
    fun variablesAreLeftOutOfTheLength() {
        // Without `{{name}}`, both read "Hello there" (11) — the long variable name changes nothing.
        myFixture.addFileToProject("locales/en/common.json", """{"hi": "Hello there {{n}}"}""")
        assertTrue(lengthWarnings("locales/fr/common.json", """{"hi": "Hello there {{aVeryLongVariableName}}"}""").isEmpty())
    }

    @Test
    fun theThresholdsAreOptions() {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Your title"}""")
        val strict = ValueLengthInspection().apply { maxRatioPercent = 120 }
        val warnings = lengthWarnings("locales/fr/common.json", """{"title": "Votre intitulé"}""", strict)
        assertTrue(warnings.single().startsWith("Translation is 140%"), "$warnings")
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.addFileToProject("locales/fr/common.json", """{"save": "Enregistrer toutes les modifications"}""")
        assertTrue(lengthWarnings("locales/en/common.json", """{"save": "Save changes"}""").isEmpty())
    }

    @Test
    fun yamlFilesAreCompared() {
        myFixture.addFileToProject("locales/en/common.yml", "save: Save changes\n")
        val warnings = lengthWarnings("locales/fr/common.yml", "save: Enregistrer toutes les modifications\n")
        Assertions.assertEquals(1, warnings.size, "$warnings")
    }

    @Test
    fun lengthsAreMeasuredWithoutVariablesInCodePoints() {
        Assertions.assertEquals(11, ValueLengthInspection.measuredLength("Hello {{name}} there"))
        Assertions.assertEquals(3, ValueLengthInspection.measuredLength("été"))
        Assertions.assertEquals(2, ValueLengthInspection.measuredLength("🎉!"))
        Assertions.assertNull(ValueLengthInspection.lengthRatioPercent("anything", "Short", 10))
    }
}
