package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan.Form
import com.ibrahimdans.i18n.plugin.translate.PluralTranslationPlan.Plan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluralTranslationPlanTest {

    private val english = mapOf("one" to "{{count}} file", "other" to "{{count}} files")

    private fun forms(plan: Plan): List<Form> = (plan as Plan.Forms).forms

    @Test
    fun `Russian gets its four forms, few and many from other and marked for review`() {
        assertEquals(
            listOf(
                Form("one", "{{count}} file", needsReview = false),
                Form("few", "{{count}} files", needsReview = true),
                Form("many", "{{count}} files", needsReview = true),
                Form("other", "{{count}} files", needsReview = false),
            ),
            forms(PluralTranslationPlan.of(english, "ru"))
        )
    }

    @Test
    fun `Japanese gets other only, no one that does not exist`() {
        assertEquals(listOf(Form("other", "{{count}} files", needsReview = false)), forms(PluralTranslationPlan.of(english, "ja")))
    }

    @Test
    fun `Arabic gets all six forms`() {
        val plan = forms(PluralTranslationPlan.of(english, "ar"))
        assertEquals(listOf("zero", "one", "two", "few", "many", "other"), plan.map { it.category })
        assertEquals(listOf("zero", "two", "few", "many"), plan.filter { it.needsReview }.map { it.category })
    }

    @Test
    fun `from Russian to English, each form comes from the form of the same name`() {
        val russian = mapOf("one" to "{{count}} файл", "few" to "{{count}} файла", "many" to "{{count}} файлов", "other" to "{{count}} файла")
        assertEquals(
            listOf(Form("one", "{{count}} файл", false), Form("other", "{{count}} файла", false)),
            forms(PluralTranslationPlan.of(russian, "en-GB"))
        )
    }

    @Test
    fun `a target form already filled is not asked again`() {
        val plan = forms(PluralTranslationPlan.of(english, "ru", existing = mapOf("one" to "{{count}} файл", "few" to " ")))
        assertEquals(listOf("few", "many", "other"), plan.map { it.category })
    }

    @Test
    fun `a target with every form filled needs nothing`() {
        assertTrue(forms(PluralTranslationPlan.of(english, "fr", existing = mapOf("one" to "a", "other" to "b"))).isEmpty())
    }

    @Test
    fun `French gets its large-number many only when asked for`() {
        assertEquals(listOf("one", "other"), forms(PluralTranslationPlan.of(english, "fr")).map { it.category })
        assertEquals(listOf("one", "many", "other"), forms(PluralTranslationPlan.of(english, "fr", largeNumberForms = true)).map { it.category })
    }

    @Test
    fun `a form with no source of its category and no other is left out`() {
        assertEquals(listOf("one"), forms(PluralTranslationPlan.of(mapOf("one" to "{{count}} file"), "ru")).map { it.category })
    }

    @Test
    fun `an ICU plural is not a text to translate`() {
        assertEquals(Plan.IcuPlural, PluralTranslationPlan.of(mapOf("other" to "{count, plural, one {# file} other {# files}}"), "ru"))
        assertTrue(PluralTranslationPlan.isIcuPlural("You have {n, selectordinal, one {#st} other {#th}} place"))
        assertFalse(PluralTranslationPlan.isIcuPlural("Hello {name}, {count} files"))
    }

    @Test
    fun `i18next suffixes and nested groups name the same categories`() {
        assertEquals(english, PluralTranslationPlan.formsOf(mapOf("file_one" to "{{count}} file", "file_other" to "{{count}} files")))
        assertEquals(english, PluralTranslationPlan.formsOf(mapOf("one" to "{{count}} file", "other" to "{{count}} files")))
        assertEquals(mapOf("few" to "x"), PluralTranslationPlan.formsOf(mapOf("file.few" to "x", "file.title" to "y"), pluralSeparator = "."))
    }
}
