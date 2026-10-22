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
}
