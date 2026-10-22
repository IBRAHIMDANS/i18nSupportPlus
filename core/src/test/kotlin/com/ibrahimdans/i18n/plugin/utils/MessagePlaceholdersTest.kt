package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessagePlaceholdersTest {

    private fun variables(text: String): List<String> =
        MessagePlaceholders.variableRanges(text).map { text.substring(it) }

    @Test
    fun `reads an i18next variable whole`() {
        assertEquals(listOf("{{count}}"), variables("{{count}} items"))
    }

    @Test
    fun `reads an i18n-js variable whole, not as a single-brace one`() {
        assertEquals(listOf("%{count}"), variables("%{count} boxes"))
    }

    @Test
    fun `reads an ICU argument`() {
        assertEquals(listOf("{name}"), variables("Hi {name}"))
    }

    @Test
    fun `reads printf variables`() {
        assertEquals(listOf("%s", "%1\$s", "%d"), variables("%s of %1\$s, %d left"))
    }

    @Test
    fun `ranges point at the variables in place`() {
        val text = "Hi {name}, {{count}} new"
        assertEquals(listOf(3..8, 11..19), MessagePlaceholders.variableRanges(text))
    }

    @Test
    fun `an ICU plural block is not one variable`() {
        // Only the innermost braces hold no brace: the block itself is left to PlaceholderMask.
        assertEquals(listOf("{# item}", "{# items}"), variables("{count, plural, one {# item} other {# items}}"))
    }

    @Test
    fun `plain text has no variable`() {
        assertEquals(emptyList<IntRange>(), MessagePlaceholders.variableRanges("Hello world"))
    }
}
