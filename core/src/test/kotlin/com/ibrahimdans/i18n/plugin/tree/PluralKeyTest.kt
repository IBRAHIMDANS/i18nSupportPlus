package com.ibrahimdans.i18n.plugin.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule that takes a key back from the *form* stored in a translation file to the key the
 * source code writes — the one `t('cart.item', { count })` passes, never `cart.item_other`.
 */
class PluralKeyTest {

    @Test
    fun `a CLDR category is dropped`() {
        for (category in listOf("zero", "one", "two", "few", "many", "other")) {
            assertEquals("cart.item", PluralKey.stripSuffix("cart.item_$category", "-"))
        }
    }

    @Test
    fun `an ordinal form is dropped with its infix, as the code writes the bare key`() {
        for (category in listOf("zero", "one", "two", "few", "many", "other")) {
            assertEquals("race.place", PluralKey.stripSuffix("race.place_ordinal_$category", "-"))
        }
    }

    @Test
    fun `a key merely named like the ordinal infix is kept`() {
        assertEquals("x_ordinal", PluralKey.stripSuffix("x_ordinal", "-"))
        assertEquals(mapOf("x_ordinal" to listOf("x_ordinal")), PluralKey.groupForms(listOf("x_ordinal")))
    }

    @Test
    fun `ordinal forms make their own group, apart from the cardinal ones`() {
        // Ordinal categories are not the cardinal ones (`en`: one/two/few/other against one/other):
        // a locale is compared on each group separately.
        val groups = PluralKey.groupForms(listOf("item_one", "item_other", "item_ordinal_one", "item_ordinal_other"))
        assertEquals(listOf("item_one", "item_other"), groups["item"])
        assertEquals(listOf("item_ordinal_one", "item_ordinal_other"), groups["item_ordinal"])
    }

    @Test
    fun `the other form of a CLDR form stays in its own group`() {
        assertEquals("cart.item_other", PluralKey.otherFormOf("cart.item_few"))
        assertEquals("race.place_ordinal_other", PluralKey.otherFormOf("race.place_ordinal_few"))
        assertEquals(null, PluralKey.otherFormOf("cart.item_other"), "other is the form itself")
        assertEquals(null, PluralKey.otherFormOf("cart.item"))
    }

    @Test
    fun `the forms a key is tried in follow the resolver's order`() {
        assertEquals(
            listOf(
                "k-1", "k-2", "k-5",
                "k_one", "k_other", "k_zero", "k_two", "k_few", "k_many",
                "k_ordinal_one", "k_ordinal_other", "k_ordinal_zero", "k_ordinal_two", "k_ordinal_few", "k_ordinal_many",
            ),
            PluralKey.numericForms("k", "-") + PluralKey.cldrForms("k"),
        )
    }

    @Test
    fun `a legacy numeric form is dropped, on the configured separator`() {
        assertEquals("cart.item", PluralKey.stripSuffix("cart.item-1", "-"))
        assertEquals("cart.item", PluralKey.stripSuffix("cart.item-2", "-"))
        assertEquals("cart.item", PluralKey.stripSuffix("cart.item-5", "-"))
        assertEquals("cart.item", PluralKey.stripSuffix("cart.item_2", "_"))
    }

    @Test
    fun `a key carrying no plural form is returned unchanged`() {
        assertEquals("cart.item", PluralKey.stripSuffix("cart.item", "-"))
        assertEquals("cart.item_singular", PluralKey.stripSuffix("cart.item_singular", "-"))
        // Only the counts the resolver expands: 3 is not a plural form of anything.
        assertEquals("cart.item-3", PluralKey.stripSuffix("cart.item-3", "-"))
    }

    @Test
    fun `the numeric form is not dropped when the separator differs`() {
        // Configured on "_", so "-1" is part of the key itself.
        assertEquals("cart.item-1", PluralKey.stripSuffix("cart.item-1", "_"))
    }

    @Test
    fun `nothing but the last segment is touched`() {
        assertEquals("a.item_one.b", PluralKey.stripSuffix("a.item_one.b", "-"))
    }

    @Test
    fun `the bare key covers the forms of its group, as i18next falls back on it`() {
        assertEquals(true, PluralKey.bareKeyCoversForms("ago", listOf("ago", "ago_other")))
        assertEquals(false, PluralKey.bareKeyCoversForms("ago", listOf("ago_other", "agony")))
    }

    @Test
    fun `a nested plural form stands for its other sibling`() {
        assertEquals("steps.other", PluralKey.nestedOtherForm("steps.few", "."))
        assertEquals("a:b/other", PluralKey.nestedOtherForm("a:b/one", "/"))
        assertEquals("other", PluralKey.nestedOtherForm("zero", "."))
        assertEquals(null, PluralKey.nestedOtherForm("steps.other", "."), "other is the form itself")
        assertEquals(null, PluralKey.nestedOtherForm("steps.title", "."))
        assertEquals(null, PluralKey.nestedOtherForm("steps_few", "."), "the flat form is stripSuffix's")
    }
}
