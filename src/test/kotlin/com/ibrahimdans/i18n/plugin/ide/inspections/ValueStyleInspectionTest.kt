package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.ValueStyleInspection
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueStyleInspectionTest : PlatformBaseTest() {

    private val messages = listOf("Final ", "Leading or trailing whitespace")

    /** The style warnings on [translated] at `locales/<locale>/common.<ext>`, compared with [reference] in `en`. */
    private fun warnings(
        reference: String,
        translated: String,
        locale: String = "fr",
        ext: String = "json",
        inspection: ValueStyleInspection = ValueStyleInspection(),
    ): List<String> {
        myFixture.enableInspections(inspection)
        myFixture.addFileToProject("locales/en/common.$ext", reference)
        val file = myFixture.addFileToProject("locales/$locale/common.$ext", translated)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { d -> messages.any { d.startsWith(it) } }
    }

    @Test
    fun aLostFullStopIsReported() {
        val warnings = warnings("""{"save": "Save.", "name": "Name:"}""", """{"save": "Enregistrer", "name": "Nom"}""")
        assertEquals(
            setOf("Final '.' of the reference locale is missing here", "Final ':' of the reference locale is missing here"),
            warnings.toSet()
        )
    }

    @Test
    fun anExtraFinalMarkIsReported() {
        val warnings = warnings("""{"save": "Save"}""", """{"save": "Enregistrer !"}""")
        assertEquals(listOf("Final '!' is not in the reference locale"), warnings)
    }

    /** Arabic writes `؟`, CJK `。`: the same marks. */
    @Test
    fun scriptSpecificMarksAreTheSame() {
        assertTrue(warnings("""{"q": "Save?"}""", """{"q": "حفظ؟"}""", locale = "ar").isEmpty())
    }

    @Test
    fun aCjkFullStopIsTheSameMark() {
        assertTrue(warnings("""{"s": "Saved."}""", """{"s": "保存しました。"}""", locale = "ja").isEmpty())
    }

    /** French typography puts a space before the colon: the value still ends with `:`. */
    @Test
    fun frenchSpacingBeforeAColonIsNotReported() {
        val warnings = warnings("""{"name": "Name:", "q": "Sure?"}""", "{\"name\": \"Nom :\", \"q\": \"Vraiment ?\"}")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aDifferentMarkOnBothSidesIsATranslatorsChoice() {
        assertTrue(warnings("""{"go": "Go."}""", """{"go": "Allez !"}""").isEmpty())
    }

    @Test
    fun aTrailingSpaceIsReported() {
        val warnings = warnings("""{"save": "Save"}""", """{"save": "Enregistrer "}""")
        assertEquals(listOf("Leading or trailing whitespace differs from the reference locale"), warnings)
    }

    @Test
    fun aDisabledCheckIsSilent() {
        val inspection = ValueStyleInspection().apply {
            checkPunctuation = false
            checkTrailingWhitespace = false
        }
        assertTrue(warnings("""{"save": "Save."}""", """{"save": "Enregistrer "}""", inspection = inspection).isEmpty())
    }

    @Test
    fun theQuickFixAddsTheFullStop() {
        warnings("""{"save": "Save."}""", """{"save": "Enregistrer"}""")
        val fix = myFixture.getAllQuickFixes().single { it.text == "Align with the reference locale" }
        myFixture.launchAction(fix)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("""{"save": "Enregistrer."}""", myFixture.editor.document.text)
    }

    @Test
    fun yamlValuesAreCompared() {
        val warnings = warnings("save: Save.\n", "save: Enregistrer\n", ext = "yaml")
        assertEquals(listOf("Final '.' of the reference locale is missing here"), warnings)
    }

    @Test
    fun theFinalMarkHelpers() {
        assertEquals("…", ValueStyleInspection.finalMark("Loading..."))
        assertEquals("?", ValueStyleInspection.finalMark("حفظ؟"))
        assertEquals(null, ValueStyleInspection.finalMark("Save"))
        assertEquals("Nom", ValueStyleInspection.withoutFinalMark("Nom :"))
        assertEquals("Chargement...", ValueStyleInspection.withFinalMark("Chargement", "Loading..."))
    }
}
