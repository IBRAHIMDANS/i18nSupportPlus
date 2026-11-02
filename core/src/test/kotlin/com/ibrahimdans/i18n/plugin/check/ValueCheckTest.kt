package com.ibrahimdans.i18n.plugin.check

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueCheckTest {

    @Test
    fun `braces are balanced across lines`() {
        assertTrue(ValueCheck.balanced("{n, plural,\n one {x}\n other {y}\n}"))
        assertFalse(ValueCheck.balanced("Hi {name"))
        assertFalse(ValueCheck.balanced("Hi name}"))
        assertFalse(ValueCheck.balanced("}{"))
    }

    @Test
    fun `a value with no placeholder lacks none`() {
        assertEquals(emptySet<String>(), ValueCheck.missingPlaceholders("Bonjour", "Hello {name}"))
        assertEquals(setOf("{age}"), ValueCheck.missingPlaceholders("Bonjour {name}", "Hello {name} {age}"))
        assertEquals(setOf("%s"), ValueCheck.extraPlaceholders("%s", "Hello"))
    }

    @Test
    fun `a one-word ICU form is text, not a placeholder`() {
        val en = "{n, plural, =0 {none} other {# items}}"
        val fr = "{n, plural, =0 {rien} other {# éléments}}"
        assertEquals(emptySet<String>(), ValueCheck.missingPlaceholders(fr, en))
        assertEquals(emptySet<String>(), ValueCheck.extraPlaceholders(fr, en))
        assertEquals(emptySet<String>(), ValueCheck.extraPlaceholders("{g, select, male {il} other {iel}}", "{g, select, male {he} other {they}}"))
        assertEquals(emptySet<String>(), ValueCheck.extraPlaceholders("{p, selectordinal, one {premier} other {#e}}", "{p, selectordinal, one {first} other {#th}}"))
    }

    @Test
    fun `a placeholder inside a longer ICU form or outside the block is still compared`() {
        assertEquals(setOf("{name}"), ValueCheck.missingPlaceholders("{n, plural, other {# éléments pour {nom}}}", "{n, plural, other {# items for {name}}}"))
        assertEquals(setOf("{user}"), ValueCheck.missingPlaceholders("{n, plural, =0 {rien} other {#}} {x}", "{n, plural, =0 {none} other {#}} {user}"))
        assertEquals(setOf("{name}"), ValueCheck.missingPlaceholders("{g, select, other {{nom}}}", "{g, select, other {{name}}}"))
    }

    @Test
    fun `run accepts translated one-word ICU forms`() {
        val problems = ValueCheck.run(mapOf("a" to mapOf("en" to "{n, plural, =0 {none} other {# items}}", "fr" to "{n, plural, =0 {rien} other {# éléments}}")), "en")
        assertEquals(emptyList<ValueProblem>(), problems["fr"])
    }

    @Test
    fun `tags are compared as a multiset, in any order`() {
        assertEquals(emptySet<String>(), ValueCheck.missingTags("<b>a</b> <1>b</1>", "<1>b</1> <b>a</b>"))
        assertEquals(setOf("<br/>"), ValueCheck.missingTags("a <br/>", "a <br/> b <br/>"))
        assertEquals(setOf("<i>"), ValueCheck.extraTags("<i>a", "a"))
        assertEquals(emptySet<String>(), ValueCheck.extraTags("a < b", "a"))
    }

    @Test
    fun `an ICU block only needs its other form`() {
        assertEquals(emptyList<IcuIssue>(), ValueCheck.icuIssues("{n, plural, other {# items}}"))
        assertEquals(emptyList<IcuIssue>(), ValueCheck.icuIssues("{n, plural, =0 {none} other {# items}}"))
        assertEquals(emptyList<IcuIssue>(), ValueCheck.icuIssues("{n, plural, one {# item} other {# items}}"))
        assertEquals(listOf(IcuIssue("plural")), ValueCheck.icuIssues("{n, plural, one {# item}}"))
        assertEquals(listOf(IcuIssue("select")), ValueCheck.icuIssues("{g, select, male {x}}"))
    }

    @Test
    fun `run accepts an English plural with its other form only`() {
        val problems = ValueCheck.run(mapOf("a" to mapOf("en" to "{n, plural, other {# items}}", "fr" to "{n, plural, =0 {aucun élément} other {# éléments}}")), "en")
        assertEquals(emptyList<ValueProblem>(), problems["en"])
        assertEquals(emptyList<ValueProblem>(), problems["fr"])
    }

    @Test
    fun `a plural form the reference lacks is compared to its other form, the count left out`() {
        val problems = ValueCheck.run(
            mapOf(
                "item_one" to mapOf("en" to "{count} item in {cart}", "ru" to "{count} товар в {cart}"),
                "item_other" to mapOf("en" to "{count} items in {cart}", "ru" to "{count} товаров в {cart}"),
                "item_few" to mapOf("ru" to "товара в {basket}"),
            ),
            "en",
        )
        assertEquals(
            listOf(
                ValueProblem("item_few", "Placeholder '{cart}' of the reference locale is missing"),
                ValueProblem("item_few", "Placeholder '{basket}' is not in the reference locale"),
            ),
            problems["ru"],
        )
    }

    @Test
    fun `the reference key of a key is itself, or the other form of a plural group`() {
        val reference = setOf("item_one", "item_other", "title")
        assertEquals("title", ValueCheck.referenceKeyOf("title") { it in reference })
        assertEquals("item_other", ValueCheck.referenceKeyOf("item_few") { it in reference })
        assertEquals(null, ValueCheck.referenceKeyOf("step_few") { it in reference })
        assertTrue(ValueCheck.isPluralForm("item_few") { it in reference })
        assertFalse(ValueCheck.isPluralForm("title") { it in reference })
    }

    @Test
    fun `run leaves a blank value to the locale check and sorts by key`() {
        val problems = ValueCheck.run(
            mapOf(
                "b" to mapOf("en" to "{x}", "fr" to "{y}"),
                "a" to mapOf("en" to "Hi", "fr" to "{"),
                "c" to mapOf("en" to "{x}", "fr" to " "),
            ),
            "en",
        )
        assertEquals(emptyList<ValueProblem>(), problems["en"])
        assertEquals(listOf("a", "b", "b"), problems.getValue("fr").map { it.key })
    }

    @Test
    fun `run compares nothing to an unbalanced reference, but reports it`() {
        val problems = ValueCheck.run(mapOf("a" to mapOf("en" to "Hi {name", "fr" to "Salut {x}")), "en")
        assertEquals(listOf(ValueProblem("a", "Unbalanced braces")), problems["en"])
        assertEquals(emptyList<ValueProblem>(), problems["fr"])
    }

}
