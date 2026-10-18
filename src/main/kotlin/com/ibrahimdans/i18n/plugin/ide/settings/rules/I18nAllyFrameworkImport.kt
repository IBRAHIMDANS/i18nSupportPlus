package com.ibrahimdans.i18n.plugin.ide.settings.rules

import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.rules.KeyRules

/**
 * Why an entry of an i18n Ally custom framework was not taken over.
 */
enum class NotImportedReason {
    /** A `languageIds` entry no language integration of the plugin covers. */
    UNSUPPORTED_LANGUAGE,

    /** A `usageMatchRegex` that is not a plain call to a function on the key. */
    NOT_A_FUNCTION_CALL,

    /** A `usageMatchRegex` that is a call, but none of the framework's languages is supported. */
    NO_SUPPORTED_LANGUAGE,

    /** A rule that an existing rule, or one imported before it, already declares. */
    DUPLICATE_RULE,

    /** A `refactorTemplates` entry that does not write a call on the key. */
    TEMPLATE_NOT_A_CALL,

    /** A setting with no equivalent here: `monopoly`, `scopeRangeRegex`… */
    NOT_APPLICABLE,
}

/** An [entry] of the framework file, as written, left out for [reason]. */
data class NotImported(val entry: String, val reason: NotImportedReason)

/** What reading an i18n Ally custom framework gives. */
sealed interface FrameworkImport {

    /**
     * The [rules] to add, the [callTemplates] proposed for the user to set on a module, and what
     * was [notImported]. Nothing is written yet: the caller shows it and applies [rules] on confirmation.
     */
    data class Plan(
        val rules: List<EditorRuleState>,
        val callTemplates: List<String>,
        val notImported: List<NotImported>,
    ) : FrameworkImport

    /**
     * The file is not a framework definition: unreadable at [line], or, when [line] is 0, holding
     * none of the keys a framework is made of.
     */
    data class Invalid(val line: Int) : FrameworkImport
}

/**
 * Takes over an i18n Ally (VS Code) custom framework, `.vscode/i18n-ally-custom-framework.yml`,
 * as *Key assistance rules*.
 *
 * The import is partial by design. i18n Ally finds keys with free regular expressions, while a rule
 * names a function whose call the language integrations resolve through the PSI — which is what
 * gives references, completion and rename. Only a `usageMatchRegex` that is a plain call on the
 * key, `[^\w\d]t\(['"`]({key})['"`]`, becomes a trigger rule; any other is reported. Each
 * `refactorTemplates` entry becomes a proposed *call template*, left to the user to set on a module,
 * since a framework file does not say which module it is for.
 *
 * The file is read by [FrameworkYaml], which needs neither the optional YAML plugin nor a library.
 */
object I18nAllyFrameworkImport {

    /** Where i18n Ally reads the custom framework, relative to the project root. */
    const val CONFIG_PATH = ".vscode/i18n-ally-custom-framework.yml"

    /** i18n Ally's `languageIds` (VS Code language ids) by the rule language that covers them. */
    private val LANGUAGES = mapOf(
        "javascript" to "js",
        "typescript" to "js",
        "javascriptreact" to "js",
        "typescriptreact" to "js",
        "vue" to "js",
        "php" to "php",
    )

    /** The keys read; any other top-level key is reported as not applicable. */
    private val READ_KEYS = setOf("languageIds", "usageMatchRegex", "refactorTemplates")

    /**
     * A plain call on the key: an optional word boundary, the escaped callee, `\(`, optional blanks
     * and quote class, then the key group. What follows the key — the closing quote — is not read:
     * the integrations take the call's first argument as the key whatever closes it.
     */
    private val CALL_REGEX = Regex(
        """^(?:\^|\\b|\[\^[^\]]*]|\(\?<!\[[^\]]*]\)|\(\?<!\\[wb]\))?""" +
            """((?:[A-Za-z0-9_]|\\\$|\\\.|\.)+)""" +
            """\\\((?:\\s\*)?(?:\[[^\]]*]|['"`])?\(?\{key}\)?.*$"""
    )

    /** A callee once unescaped: identifiers joined by dots, `$t`, `i18n.t`, `__`. */
    private val CALLEE = Regex("""^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$""")

    /** The key argument of a refactor template, quoted any way: `'$1'`, `"$1"`, `` `$1` ``. */
    private val TEMPLATE_KEY = Regex("""(['"`])\$1\1""")

    /** A call template, once the key is a placeholder: a callee then parentheses. */
    private val TEMPLATE_CALL = Regex("""^[A-Za-z_$][\w$.]*\(.*\)$""")

    /**
     * What the framework [yaml] gives next to the [existing] rules. A rule is a duplicate when an
     * existing rule already makes its trigger a translation call in its language, without constraint.
     */
    fun plan(yaml: String, existing: List<EditorRuleState>): FrameworkImport {
        val root = try {
            FrameworkYaml.parse(yaml)
        } catch (e: FrameworkYamlException) {
            return FrameworkImport.Invalid(e.line)
        }
        if (root.keys.none { it in READ_KEYS }) return FrameworkImport.Invalid(0)

        val notImported = mutableListOf<NotImported>()
        val languages = strings(root["languageIds"]).mapNotNull { id ->
            LANGUAGES[id].also { if (it == null) notImported += NotImported(id, NotImportedReason.UNSUPPORTED_LANGUAGE) }
        }.distinct()

        val rules = mutableListOf<EditorRuleState>()
        for (regex in strings(root["usageMatchRegex"])) {
            val trigger = triggerOf(regex)
            when {
                trigger == null -> notImported += NotImported(regex, NotImportedReason.NOT_A_FUNCTION_CALL)
                languages.isEmpty() -> notImported += NotImported(regex, NotImportedReason.NO_SUPPORTED_LANGUAGE)
                else -> for (language in languages) {
                    if (declared(existing + rules, trigger, language)) {
                        notImported += NotImported("$trigger ($language)", NotImportedReason.DUPLICATE_RULE)
                    } else {
                        rules += EditorRuleState(id = "i18n Ally $trigger ($language)", language = language, trigger = trigger)
                    }
                }
            }
        }

        val templates = strings(root["refactorTemplates"]).mapNotNull { template ->
            callTemplateOf(template).also { if (it == null) notImported += NotImported(template, NotImportedReason.TEMPLATE_NOT_A_CALL) }
        }.distinct()

        for ((key, value) in root) {
            if (key in READ_KEYS || (key == "monopoly" && value != true)) continue
            notImported += NotImported("$key: $value", NotImportedReason.NOT_APPLICABLE)
        }
        return FrameworkImport.Plan(rules, templates, notImported)
    }

    /** The function [usageMatchRegex] matches a call of, unescaped, or null when it is not a plain call. */
    fun triggerOf(usageMatchRegex: String): String? {
        val callee = CALL_REGEX.find(usageMatchRegex.trim())?.groupValues?.get(1) ?: return null
        return callee.replace("\\", "").takeIf { CALLEE.matches(it) }
    }

    /**
     * The call template [refactorTemplate] writes: the quoted `$1` becomes [CallTemplate.KEY], and the
     * braces of a JSX or Vue expression around the call go, since the extractor writes those itself.
     * Null when what is left is not a call holding the key once.
     */
    fun callTemplateOf(refactorTemplate: String): String? {
        var call = TEMPLATE_KEY.replace(refactorTemplate.trim(), Regex.escapeReplacement(CallTemplate.KEY))
        while (call.startsWith("{") && call.endsWith("}") && !call.startsWith(CallTemplate.KEY)) {
            call = call.substring(1, call.length - 1).trim()
        }
        return call.takeIf { TEMPLATE_CALL.matches(it) && CallTemplate.isValid(it) }
    }

    private fun declared(rules: List<EditorRuleState>, trigger: String, language: String): Boolean =
        rules.any { rule ->
            !rule.exclude && rule.constraintType == KeyRules.Constraint.NONE && rule.trigger.trim() == trigger &&
                (rule.language.isBlank() || rule.language.trim().equals(language, ignoreCase = true))
        }

    /** A YAML list of strings, or a single string, as a list; anything else as nothing. */
    private fun strings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
}
