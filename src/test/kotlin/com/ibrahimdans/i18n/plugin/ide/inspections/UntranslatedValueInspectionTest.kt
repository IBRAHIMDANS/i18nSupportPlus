package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UntranslatedValueInspection
import com.intellij.profile.codeInspection.InspectionProfileManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UntranslatedValueInspectionTest : PlatformBaseTest() {

    private val message = "Value identical to the reference locale: not translated?"

    /** The warnings on [translated] at `locales/fr/common.<ext>`, compared with [reference] in `en`. */
    private fun warnings(
        reference: String,
        translated: String,
        ext: String = "json",
        inspection: UntranslatedValueInspection = UntranslatedValueInspection(),
    ): List<String> {
        myFixture.enableInspections(inspection)
        myFixture.addFileToProject("locales/en/common.$ext", reference)
        val file = myFixture.addFileToProject("locales/fr/common.$ext", translated)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return found()
    }

    private fun found(): List<String> = myFixture.doHighlighting().mapNotNull { it.description }.filter { it == message }

    @Test
    fun aCopiedSentenceIsReported() {
        val warnings = warnings(
            """{"title": "Account settings", "save": "Save"}""",
            """{"title": "Account settings", "save": "Enregistrer"}"""
        )
        assertEquals(listOf(message), warnings)
    }

    /** `OK`, `Email` and `42 items` hold one word with letters: the same in many languages. */
    @Test
    fun aSingleWordIsNotReported() {
        val warnings = warnings(
            """{"ok": "OK", "email": "Email", "count": "42 items"}""",
            """{"ok": "OK", "email": "Email", "count": "42 items"}"""
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun anIgnoredValueIsNotReported() {
        val inspection = UntranslatedValueInspection().apply { ignoredValues.add("Account settings") }
        val warnings = warnings(
            """{"title": "Account settings"}""",
            """{"title": " Account settings "}""",
            inspection = inspection
        )
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun theReferenceFileIsNeverReported() {
        myFixture.enableInspections(UntranslatedValueInspection())
        myFixture.addFileToProject("locales/fr/common.json", """{"title": "Account settings"}""")
        val file = myFixture.addFileToProject("locales/en/common.json", """{"title": "Account settings"}""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertTrue(found().isEmpty(), "${found()}")
    }

    @Test
    fun yamlValuesAreCompared() {
        assertEquals(listOf(message), warnings("title: Account settings\n", "title: Account settings\n", "yaml"))
    }

    @Test
    fun markAsIntendedAddsTheValueToTheProfile() {
        warnings("""{"title": "Account settings"}""", """{"title": "Account settings"}""")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("Account") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Mark as intended"))

        val tool = InspectionProfileManager.getInstance(project).currentProfile
            .getUnwrappedTool(UntranslatedValueInspection.SHORT_NAME, myFixture.file) as UntranslatedValueInspection
        assertEquals(listOf("Account settings"), tool.ignoredValues)
        assertTrue(found().isEmpty(), "${found()}")
    }
}
