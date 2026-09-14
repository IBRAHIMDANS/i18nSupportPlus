package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState

/**
 * The call a rule is asked about: which language, which callee, where, and with what key.
 *
 * Plain data — no PSI — so the whole rule semantics can be tested on its own. The language
 * integrations build it from the call site.
 *
 * @param language the call site's language id as rules name it (`js`, `php`).
 * @param callee the called function as written: `translate`, `$t`, `i18n.t`, `__`.
 * @param filePath the project-relative path of the file holding the call.
 * @param imports the module specifiers the file imports (`react-i18next`, `@lingui/macro`).
 * @param key the key literal as written, when there is one.
 */
data class RuleContext(
    val language: String,
    val callee: String,
    val filePath: String,
    val imports: Set<String> = emptySet(),
    val key: String? = null,
)

/** What the rules say about a call. */
enum class RuleDecision {
    /** A rule declares the call a translation call. */
    INCLUDE,

    /** A rule declares the call not a translation call, even when a framework publishes its name. */
    EXCLUDE,

    /** No rule applies: the frameworks' own names decide. */
    NONE,
}

/**
 * The *Key assistance rules* configured in the settings.
 *
 * A rule states that, in [EditorRuleState.language] (empty for every language), a call to
 * [EditorRuleState.trigger] **is** a translation call — or **is not** one when
 * [EditorRuleState.exclude] is set — provided its optional constraint holds:
 *  - [EditorRuleState.constraintType]: [Constraint.FILE_PATH], [Constraint.IMPORT] or
 *    [Constraint.KEY_PATTERN], checked against [EditorRuleState.value];
 *  - [EditorRuleState.matchMode]: [MatchMode.EXACT], [MatchMode.PREFIX] or [MatchMode.REGEX];
 *  - [EditorRuleState.negated] inverts the constraint.
 *
 * When several rules apply, the highest [EditorRuleState.priority] wins; on a tie an exclusion wins
 * — keeping a false positive out is the safer side. A rule that cannot be evaluated (unknown
 * constraint or mode, invalid regex) is ignored rather than allowed to break highlighting.
 */
object KeyRules {

    /** The constraint kinds a rule may use, as stored in [EditorRuleState.constraintType]. */
    object Constraint {
        const val NONE = ""
        const val FILE_PATH = "filePath"
        const val IMPORT = "import"
        const val KEY_PATTERN = "keyPattern"
        val ALL = listOf(NONE, FILE_PATH, IMPORT, KEY_PATTERN)
    }

    /** How a constraint's value is matched, as stored in [EditorRuleState.matchMode]. */
    object MatchMode {
        const val EXACT = "exact"
        const val PREFIX = "prefix"
        const val REGEX = "regex"
        val ALL = listOf(EXACT, PREFIX, REGEX)
    }

    /** What [rules] decide about [context]. */
    fun decide(rules: List<EditorRuleState>, context: RuleContext): RuleDecision {
        val applicable = rules.filter { applies(it, context) }
        if (applicable.isEmpty()) return RuleDecision.NONE
        val top = applicable.maxOf { it.priority }
        val winners = applicable.filter { it.priority == top }
        return if (winners.any { it.exclude }) RuleDecision.EXCLUDE else RuleDecision.INCLUDE
    }

    /** Every trigger an including rule declares for [language], for callers matching names up front. */
    fun includedTriggers(rules: List<EditorRuleState>, language: String): Set<String> =
        rules.filter { !it.exclude && it.trigger.isNotBlank() && languageMatches(it, language) }
            .mapTo(mutableSetOf()) { it.trigger.trim() }

    /** Whether [rule] can be evaluated at all: known constraint and mode, compilable regex. */
    fun isValid(rule: EditorRuleState): Boolean {
        if (rule.trigger.isBlank()) return false
        if (rule.constraintType !in Constraint.ALL) return false
        if (rule.constraintType == Constraint.NONE) return true
        val mode = rule.matchMode.ifBlank { MatchMode.EXACT }
        if (mode !in MatchMode.ALL) return false
        return mode != MatchMode.REGEX || runCatching { Regex(rule.value) }.isSuccess
    }

    private fun applies(rule: EditorRuleState, context: RuleContext): Boolean {
        if (!isValid(rule)) return false
        if (!languageMatches(rule, context.language)) return false
        if (rule.trigger.trim() != context.callee) return false
        if (rule.constraintType == Constraint.NONE) return true
        val holds = constraintHolds(rule, context)
        return if (rule.negated) !holds else holds
    }

    private fun languageMatches(rule: EditorRuleState, language: String): Boolean =
        rule.language.isBlank() || rule.language.trim().equals(language, ignoreCase = true)

    private fun constraintHolds(rule: EditorRuleState, context: RuleContext): Boolean {
        val candidates: Collection<String> = when (rule.constraintType) {
            Constraint.FILE_PATH -> listOf(context.filePath)
            Constraint.IMPORT -> context.imports
            Constraint.KEY_PATTERN -> listOfNotNull(context.key)
            else -> return false
        }
        return candidates.any { matches(rule, it) }
    }

    private fun matches(rule: EditorRuleState, candidate: String): Boolean =
        when (rule.matchMode.ifBlank { MatchMode.EXACT }) {
            MatchMode.EXACT -> candidate == rule.value
            MatchMode.PREFIX -> candidate.startsWith(rule.value)
            MatchMode.REGEX -> Regex(rule.value).containsMatchIn(candidate)
            else -> false
        }
}
