package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The reference key a translation is translated from, without a project. */
class ReviewSourcesTest {

    private val english = mapOf("cart.item_one" to "{{count}} item", "cart.item_other" to "{{count}} items", "save" to "Save", "blank" to "")

    private fun sourceOf(key: String, reference: Map<String, String> = english, separator: String = "-", keySeparator: String = ".") =
        ReviewSources.sourceTextOf(key, separator, keySeparator, reference::get)

    @Test
    fun `a key the reference holds is its own source`() {
        assertEquals("Save", sourceOf("save"))
        assertEquals("{{count}} item", sourceOf("cart.item_one"))
    }

    @Test
    fun `a CLDR form the reference lacks reads its other form`() {
        assertEquals("{{count}} items", sourceOf("cart.item_few"))
        assertEquals("{{count}} items", sourceOf("cart.item_many"))
        assertEquals("{{count}} items", sourceOf("cart.item_few", separator = "_"), "CLDR forms always use `_`")
    }

    @Test
    fun `a legacy numeric form reads the widest count the reference holds`() {
        assertEquals("{{count}} items", sourceOf("item-5", mapOf("item-1" to "{{count}} item", "item-2" to "{{count}} items")))
        assertEquals("{{count}} items", sourceOf("item_5", mapOf("item_1" to "{{count}} item", "item_2" to "{{count}} items"), "_"))
        assertEquals("{{count}} many", sourceOf("item-2", mapOf("item-1" to "{{count}} item", "item-5" to "{{count}} many")))
        assertNull(sourceOf("item-5", mapOf("item-1" to "{{count}} item")), "the singular stands for no plural")
    }

    @Test
    fun `no source when the reference holds neither the key nor its other form`() {
        assertNull(sourceOf("missing"))
        assertNull(sourceOf("blank"), "an empty value is no source")
        assertNull(sourceOf("cart.item_other", mapOf("cart.item_one" to "{{count}} item")), "other has no other to fall back to")
        assertNull(sourceOf("steps_few", mapOf("steps_one" to "One step")))
    }

    @Test
    fun `a nested plural form the reference lacks reads its other form`() {
        val nested = mapOf("steps.one" to "One step", "steps.other" to "{{count}} steps")
        assertEquals("{{count}} steps", sourceOf("steps.few", nested))
        assertEquals("{{count}} steps", sourceOf("steps/many", mapOf("steps/other" to "{{count}} steps"), keySeparator = "/"))
        assertEquals("{{count}} steps", sourceOf("few", mapOf("other" to "{{count}} steps")), "the dialog asks by last segment")
        assertNull(sourceOf("steps.few", mapOf("steps.one" to "One step")), "no other form to read")
        assertNull(sourceOf("menu.title", mapOf("menu.other" to "Other")), "a key that is no category reads nothing")
    }
}
