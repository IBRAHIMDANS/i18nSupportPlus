package com.ibrahimdans.i18n.extensions.technology.vuei18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VueI18nPluralsTest {

    @Test
    fun `a value without a pipe is no plural`() {
        assertEquals(emptyList<String>(), VueI18nPlurals.branchesOf("Hello {name}!"))
    }

    @Test
    fun `each pipe separates a branch`() {
        assertEquals(listOf("no items ", " one item ", " {count} items"), VueI18nPlurals.branchesOf("no items | one item | {count} items"))
    }

    @Test
    fun `a pipe inside braces is no separator`() {
        assertEquals(emptyList<String>(), VueI18nPlurals.branchesOf("Home {'|'} Docs"))
        assertEquals(2, VueI18nPlurals.branchesOf("A {'|'} B | C").size)
    }

    @Test
    fun `each language needs a branch per form an integer reaches`() {
        assertEquals(listOf("one", "other"), VueI18nPlurals.formsOf("en"))
        assertEquals(listOf("one", "other"), VueI18nPlurals.formsOf("fr"))
        assertEquals(listOf("one", "few", "many"), VueI18nPlurals.formsOf("ru"))
        assertEquals(listOf("one", "few", "many"), VueI18nPlurals.formsOf("pl-PL"))
        assertEquals(listOf("one", "few", "other"), VueI18nPlurals.formsOf("cs"))
        assertEquals(listOf("other"), VueI18nPlurals.formsOf("ja"))
    }
}
