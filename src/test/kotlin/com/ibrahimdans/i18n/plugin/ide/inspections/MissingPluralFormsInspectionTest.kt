package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.EmptyTranslationValueInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingPluralFormsInspection
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A plural group must hold every form its locale's language uses — and nothing else is asked. */
class MissingPluralFormsInspectionTest : PlatformBaseTest() {

    private fun pluralWarnings(
        path: String,
        content: String,
        inspection: MissingPluralFormsInspection = MissingPluralFormsInspection()
    ): List<String> {
        myFixture.enableInspections(inspection)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .mapNotNull { it.description }
            .filter { it.startsWith("Plural ") }
    }

    @Test
    fun russianNeedsFewAndMany() {
        val warnings = pluralWarnings("locales/ru/common.json", """{"item_one": "предмет", "item_other": "предметов"}""")
        Assertions.assertEquals(listOf("Plural 'item' lacks the form(s) few, many that 'ru' uses"), warnings)
    }

    @Test
    fun englishOneAndOtherIsComplete() {
        assertTrue(pluralWarnings("locales/en/common.json", """{"item_one": "item", "item_other": "items"}""").isEmpty())
    }

    @Test
    fun japaneseOtherAloneIsComplete() {
        assertTrue(pluralWarnings("locales/ja/common.json", """{"item_other": "アイテム"}""").isEmpty())
    }

    @Test
    fun aFormTooManyIsNeverReported() {
        // `few` means nothing in English: extra, not missing.
        assertTrue(pluralWarnings("locales/en/common.json", """{"item_one": "a", "item_few": "b", "item_other": "c"}""").isEmpty())
    }

    @Test
    fun aKeyMerelyNamedLikeAFormIsNotAPlural() {
        assertTrue(pluralWarnings("locales/ru/common.json", """{"step_one": "Шаг первый", "title": "Заголовок"}""").isEmpty())
    }

    @Test
    fun nestedGroupsAreCheckedInTheirObject() {
        val warnings = pluralWarnings(
            "locales/pl/common.json",
            """{"cart": {"item_one": "produkt", "item_few": "produkty", "item_other": "produktów"}}"""
        )
        Assertions.assertEquals(listOf("Plural 'item' lacks the form(s) many that 'pl' uses"), warnings)
    }

    @Test
    fun frenchManyOnlyWithTheOption() {
        val content = """{"file_one": "fichier", "file_other": "fichiers"}"""
        assertTrue(pluralWarnings("locales/fr/common.json", content).isEmpty())
        val strict = MissingPluralFormsInspection().apply { largeNumberForms = true }
        Assertions.assertEquals(
            listOf("Plural 'file' lacks the form(s) many that 'fr' uses"),
            pluralWarnings("locales/fr/other.json", content, strict)
        )
    }

    @Test
    fun yamlGroupsAreChecked() {
        val warnings = pluralWarnings("locales/uk/common.yml", "item_one: предмет\nitem_other: предметів\n")
        Assertions.assertEquals(listOf("Plural 'item' lacks the form(s) few, many that 'uk' uses"), warnings)
    }

    @Test
    fun theQuickFixAddsTheMissingFormsEmpty() {
        pluralWarnings("locales/ru/common.json", """{"cart": {"item_one": "предмет", "item_other": "предметов"}}""")
        val fix = myFixture.getAllQuickFixes().single { it.text.startsWith("Add item_few, item_many") }
        myFixture.launchActionAndWait(fix)

        val text = FileDocumentManager.getInstance().getDocument(myFixture.file.virtualFile)!!.text
        assertTrue(text.contains("\"item_few\": \"\"") && text.contains("\"item_many\": \"\""), text)
        assertTrue(text.contains("\"item_one\": \"предмет\""), text)

        myFixture.enableInspections(EmptyTranslationValueInspection::class.java)
        Assertions.assertEquals(
            2,
            myFixture.doHighlighting().count { it.description == "Translation value is empty" },
            "the new forms are left for EmptyTranslationValue to point at"
        )
        assertTrue(
            myFixture.doHighlighting().none { it.description?.startsWith("Plural ") == true },
            "the group is complete once fixed"
        )
    }
}
