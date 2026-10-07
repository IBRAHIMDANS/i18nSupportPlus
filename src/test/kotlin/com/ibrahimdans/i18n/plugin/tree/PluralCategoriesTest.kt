package com.ibrahimdans.i18n.plugin.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluralCategoriesTest {

    @Test
    fun `each language needs the categories an integer count reaches`() {
        assertEquals(setOf("one", "other"), PluralCategories.of("en"))
        assertEquals(setOf("one", "few", "many", "other"), PluralCategories.of("ru"))
        assertEquals(setOf("other"), PluralCategories.of("ja"))
        assertEquals(setOf("zero", "one", "two", "few", "many", "other"), PluralCategories.of("ar"))
    }

    @Test
    fun `a decimal-only category is not asked for`() {
        // Czech `many` is for decimals: `t('key', { count: 5 })` lands on `other`.
        assertEquals(setOf("one", "few", "other"), PluralCategories.of("cs"))
    }

    @Test
    fun `the large-number many is only asked for on demand`() {
        assertEquals(setOf("one", "other"), PluralCategories.of("fr"))
        assertEquals(setOf("one", "many", "other"), PluralCategories.of("fr", largeNumberForms = true))
        // A language without such a form is unchanged by the option.
        assertEquals(setOf("one", "other"), PluralCategories.of("de", largeNumberForms = true))
    }

    @Test
    fun `a region or a script is read as its language`() {
        assertEquals(PluralCategories.of("pt"), PluralCategories.of("pt-BR"))
        assertEquals(PluralCategories.of("zh"), PluralCategories.of("zh_Hant"))
        assertEquals(PluralCategories.of("ru"), PluralCategories.of("RU"))
    }

    @Test
    fun `an unknown language falls back to one and other`() {
        assertEquals(setOf("one", "other"), PluralCategories.of("xx"))
    }
}
