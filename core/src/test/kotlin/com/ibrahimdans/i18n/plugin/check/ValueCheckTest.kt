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
    fun `tags are compared as a multiset, in any order`() {
        assertEquals(emptySet<String>(), ValueCheck.missingTags("<b>a</b> <1>b</1>", "<1>b</1> <b>a</b>"))
        assertEquals(setOf("<br/>"), ValueCheck.missingTags("a <br/>", "a <br/> b <br/>"))
        assertEquals(setOf("<i>"), ValueCheck.extraTags("<i>a", "a"))
        assertEquals(emptySet<String>(), ValueCheck.extraTags("a < b", "a"))
    }

    @Test
    fun `an ICU block needs its forms, a language without one form excepted`() {
        val plural = "{n, plural, other {x}}"
        assertEquals(listOf(IcuIssue("plural", IcuIssue.Lacking.ONE_OR_ZERO)), ValueCheck.icuIssues(plural, needsOne = true))
        assertEquals(emptyList<IcuIssue>(), ValueCheck.icuIssues(plural, needsOne = false))
        assertEquals(listOf(IcuIssue("select", IcuIssue.Lacking.OTHER)), ValueCheck.icuIssues("{g, select, male {x}}", needsOne = true))
        assertFalse(ValueCheck.pluralNeedsOne("zh-TW"))
        assertTrue(ValueCheck.pluralNeedsOne("pt_BR"))
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

    @Test
    fun `run asks a plural of the locale an alias stands for`() {
        val problems = ValueCheck.run(mapOf("a" to mapOf("cn" to "{n, plural, other {x}}")), "cn", pluralLocale = { "zh" })
        assertEquals(emptyList<ValueProblem>(), problems["cn"])
    }
}
