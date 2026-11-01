package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationCoverage
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
        val stats = TranslationCoverage.analyze(translations).associateBy { it.locale }
        listOf("en", "ru", "ja").forEach { locale ->
            assertEquals(2, stats.getValue(locale).total, locale)
            assertEquals(0, stats.getValue(locale).missing, "$locale: ${stats.getValue(locale).missingKeys}")
        }
    }

    private val source = { locale: String -> mockk<LocalizationSource>(relaxed = true).also {
        every { it.name } returns "$locale.json"
        every { it.parent } returns "locales"
        // A relaxed mock answers "" for these, which reads as a locale a template stated.
        every { it.locale } returns null
        every { it.namespace } returns null
    } }

    @Test
    fun syncProposesNothingForForeignCategoriesAndOnlyOtherForAMissingGroup() {
        val sources = listOf("en", "ru", "ja", "de").map(source)

        val missing = KeysSynchronizer().findMissingEntries(translations, listOf("en", "ru", "ja", "de"), sources)

        assertEquals(listOf("de" to "cart.item_other", "de" to "title"), missing.map { it.locale to it.key })
    }

    /**
     * Crowdin's export, `ago` beside `ago_other` and never `ago_one`: the group is complete where
     * it stands, missing as a whole elsewhere, and `_one` is asked nowhere.
     */
    private val bareKeyAndOther = mapOf(
        "ago" to mapOf("en" to "a minute ago", "fr" to "il y a une minute"),
        "ago_other" to mapOf("en" to "{{count}} minutes ago", "fr" to "il y a {{count}} minutes"),
        "title" to mapOf("en" to "Title", "fr" to "Titre", "de" to "Titel"),
    )

    @Test
    fun aBareKeyBesideOtherIsCompleteInStatsAndMissingAsAWholeElsewhere() {
        val stats = TranslationCoverage.analyze(bareKeyAndOther).associateBy { it.locale }
        assertEquals(0, stats.getValue("en").missing, "${stats.getValue("en").missingKeys}")
        assertEquals(0, stats.getValue("fr").missing, "${stats.getValue("fr").missingKeys}")
        assertEquals(listOf("ago_other"), stats.getValue("de").missingKeys)
    }

    @Test
    fun syncCreatesOtherForABareKeyGroupMissingAsAWhole() {
        val missing = KeysSynchronizer().findMissingEntries(bareKeyAndOther, listOf("en", "fr", "de"), listOf("en", "fr", "de").map(source))

        assertEquals(listOf("de" to "ago_other"), missing.map { it.locale to it.key })
    }

    @Test
    fun missingKeyInspectionAsksNoOneForABareKeyGroup() {
        val reference = listOf(listOf("ago"), listOf("ago_other"), listOf("title"))

        assertEquals(emptyList<List<String>>(), MissingTranslationKeyInspection.missingPaths(reference, reference))
        assertEquals(listOf(listOf("ago_other")), MissingTranslationKeyInspection.missingPaths(reference, listOf(listOf("title"))))
    }
}
