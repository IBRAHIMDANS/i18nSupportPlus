package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.rules.KeyRules.Constraint
import com.ibrahimdans.i18n.plugin.rules.KeyRules.MatchMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KeyRulesTest {

    private val call = RuleContext(language = "js", callee = "translate", filePath = "src/app/Home.tsx", imports = setOf("@acme/i18n"), key = "home:title")

    private fun rule(
        trigger: String = "translate",
        language: String = "",
        exclude: Boolean = false,
        priority: Int = 0,
        type: String = Constraint.NONE,
        value: String = "",
        mode: String = "",
        negated: Boolean = false,
    ) = EditorRuleState(id = "r", language = language, trigger = trigger, priority = priority, exclude = exclude,
        constraintType = type, value = value, matchMode = mode, negated = negated)

    @Test
    fun noRuleLeavesTheDecisionToTheFrameworks() {
        assertEquals(RuleDecision.NONE, KeyRules.decide(emptyList(), call))
        assertEquals(RuleDecision.NONE, KeyRules.decide(listOf(rule(trigger = "other")), call))
    }

    @Test
    fun aTriggerRuleIncludesOrExcludes() {
        assertEquals(RuleDecision.INCLUDE, KeyRules.decide(listOf(rule()), call))
        assertEquals(RuleDecision.EXCLUDE, KeyRules.decide(listOf(rule(exclude = true)), call))
    }

    @Test
    fun theLanguageMustMatchWhenGiven() {
        assertEquals(RuleDecision.INCLUDE, KeyRules.decide(listOf(rule(language = "JS")), call))
        assertEquals(RuleDecision.NONE, KeyRules.decide(listOf(rule(language = "php")), call))
    }

    @Test
    fun eachConstraintInEachMode() {
        val cases = listOf(
            rule(type = Constraint.FILE_PATH, value = "src/app/Home.tsx", mode = MatchMode.EXACT) to true,
            rule(type = Constraint.FILE_PATH, value = "src/legacy/", mode = MatchMode.PREFIX) to false,
            rule(type = Constraint.FILE_PATH, value = """\.tsx$""", mode = MatchMode.REGEX) to true,
            rule(type = Constraint.IMPORT, value = "@acme/", mode = MatchMode.PREFIX) to true,
            rule(type = Constraint.IMPORT, value = "react-i18next") to false,
            rule(type = Constraint.KEY_PATTERN, value = "^home:", mode = MatchMode.REGEX) to true,
            rule(type = Constraint.KEY_PATTERN, value = "home:title", negated = true) to false,
            rule(type = Constraint.FILE_PATH, value = "src/legacy/", mode = MatchMode.PREFIX, negated = true) to true,
        )
        cases.forEach { (rule, applies) ->
            val expected = if (applies) RuleDecision.INCLUDE else RuleDecision.NONE
            assertEquals(expected, KeyRules.decide(listOf(rule), call), "$rule")
        }
    }

    @Test
    fun theHighestPriorityWinsAndAnExclusionWinsATie() {
        assertEquals(RuleDecision.INCLUDE, KeyRules.decide(listOf(rule(exclude = true, priority = 1), rule(priority = 5)), call))
        assertEquals(RuleDecision.EXCLUDE, KeyRules.decide(listOf(rule(exclude = true, priority = 5), rule(priority = 5)), call))
    }

    @Test
    fun anInvalidRuleIsIgnoredNeverThrown() {
        val broken = rule(type = Constraint.KEY_PATTERN, value = "([", mode = MatchMode.REGEX)
        assertFalse(KeyRules.isValid(broken))
        assertEquals(RuleDecision.NONE, KeyRules.decide(listOf(broken), call))
        assertEquals(RuleDecision.NONE, KeyRules.decide(listOf(rule(type = "unknown")), call))
        assertEquals(RuleDecision.NONE, KeyRules.decide(listOf(rule(type = Constraint.IMPORT, value = "x", mode = "fuzzy")), call))
    }

    @Test
    fun includedTriggersPerLanguage() {
        val rules = listOf(rule(trigger = "translate", language = "js"), rule(trigger = "__", language = "php"), rule(trigger = "t", exclude = true))
        assertEquals(setOf("translate"), KeyRules.includedTriggers(rules, "js"))
        assertEquals(setOf("__"), KeyRules.includedTriggers(rules, "php"))
    }
}
