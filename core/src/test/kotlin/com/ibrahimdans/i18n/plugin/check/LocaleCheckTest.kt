package com.ibrahimdans.i18n.plugin.check

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocaleCheckTest {

    private fun missing(translations: Map<String, Map<String, String>>, locale: String) =
        LocaleCheck.run(translations, "en").first { it.locale == locale }.missing

    /** `place` written with its four English ordinal forms, `ru` holding only `other`, its one ordinal category. */
    private val ordinals = mapOf(
        "place_ordinal_one" to mapOf("en" to "{{count}}st"),
        "place_ordinal_two" to mapOf("en" to "{{count}}nd"),
        "place_ordinal_few" to mapOf("en" to "{{count}}rd"),
        "place_ordinal_other" to mapOf("en" to "{{count}}th", "ru" to "{{count}}-е"),
    )

    @Test
    fun `an ordinal group is complete with the ordinal categories of its language`() {
        assertEquals(emptyList<String>(), missing(ordinals, "ru"))
    }

    @Test
    fun `an ordinal form the language needs is missing`() {
        // English ordinals are one, two, few and other, where its cardinal plural only has one and other.
        assertEquals(listOf("place_ordinal_two"), missing(ordinals - "place_ordinal_two", "en"))
    }

    @Test
    fun `french ordinals are one and other`() {
        val french = ordinals + mapOf(
            "place_ordinal_one" to mapOf("en" to "{{count}}st", "fr" to "{{count}}er"),
            "place_ordinal_other" to mapOf("en" to "{{count}}th", "fr" to "{{count}}e"),
        )
        assertEquals(emptyList<String>(), missing(french, "fr"))
    }

    @Test
    fun `a cardinal group still takes the cardinal categories`() {
        val cardinals = mapOf(
            "item_one" to mapOf("en" to "item", "ru" to "предмет"),
            "item_other" to mapOf("en" to "items", "ru" to "предметов"),
        )
        assertEquals(listOf("item_few", "item_many"), missing(cardinals, "ru"))
    }

    @Test
    fun `cardinal and ordinal groups of one key are compared each with its own categories`() {
        val both = ordinals + mapOf(
            "place_one" to mapOf("en" to "place", "ru" to "место"),
            "place_other" to mapOf("en" to "places", "ru" to "мест"),
        )
        assertEquals(listOf("place_few", "place_many"), missing(both, "ru"))
    }
}
