package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationStatsAnalyzer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Plural categories depend on the language. Comparing forms one by one made *Sync Keys* propose
 * `item_few` for English and the stats count `item_one` as missing in Japanese.
 */
class PluralGroupingTest {

    private val translations = mapOf(
        "cart.item_one" to mapOf("en" to "{{count}} item", "ru" to "{{count}} товар"),
        "cart.item_few" to mapOf("ru" to "{{count}} товара"),
        "cart.item_many" to mapOf("ru" to "{{count}} товаров"),
        "cart.item_other" to mapOf("en" to "{{count}} items", "ru" to "{{count}} товара", "ja" to "{{count}} 個"),
        "title" to mapOf("en" to "Cart", "ru" to "Корзина", "ja" to "カート"),
    )

    @Test
    fun formsOfOnePluralAreOneGroup() {
        val groups = PluralKey.groupForms(translations.keys)
        assertEquals(setOf("cart.item", "title"), groups.keys)
        assertEquals(4, groups.getValue("cart.item").size)
    }

    /** A lone `_one` is far more likely a key named so; a lone `_other` is a plural. */
    @Test
    fun loneFormIsAGroupOnlyWhenItIsOther() {
        assertEquals(setOf("step_one"), PluralKey.groupForms(listOf("step_one")).keys)
        assertEquals(setOf("box"), PluralKey.groupForms(listOf("box_other")).keys)
    }

    @Test
    fun statsCountEachLanguageCompleteWhateverItsCategories() {
        val stats = TranslationStatsAnalyzer.analyze(translations).associateBy { it.locale }
        listOf("en", "ru", "ja").forEach { locale ->
            assertEquals(2, stats.getValue(locale).total, locale)
            assertEquals(0, stats.getValue(locale).missing, "$locale: ${stats.getValue(locale).missingKeys}")
        }
    }

    @Test
    fun syncProposesNothingForForeignCategoriesAndOnlyOtherForAMissingGroup() {
        val source = { locale: String -> mockk<LocalizationSource>(relaxed = true).also {
            every { it.name } returns "$locale.json"
            every { it.parent } returns "locales"
        } }
        val sources = listOf("en", "ru", "ja", "de").map(source)

        val missing = KeysSynchronizer().findMissingEntries(translations, listOf("en", "ru", "ja", "de"), sources)

        assertEquals(listOf("de" to "cart.item_other", "de" to "title"), missing.map { it.locale to it.key })
    }
}
