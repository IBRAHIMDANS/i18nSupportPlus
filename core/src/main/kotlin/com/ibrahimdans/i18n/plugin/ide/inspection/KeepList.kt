package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.settings.Config

/**
 * Keys the project declares as used although no code names them: a key received from an API
 * (`t(response.errorKey)`), stored in a database, read by a library at runtime. No static
 * analysis can see those, so the project says it, and *Unused translation key*, *Scan Orphans*
 * and *Cleanup Unused Keys* leave them alone.
 *
 * A rule is an exact key (`errors.timeout`), a prefix ending with the key separator (`errors.`,
 * read as `errors.*`) or a glob where `*` stands for any run of characters (`errors.*`,
 * `*.label`). A rule carrying a namespace (`common:errors.*`) applies to that namespace only;
 * one without applies to every namespace. A key written without a namespace lives in a default
 * one, so `translation:errors.*` reaches it when `translation` is a default namespace.
 *
 * Nothing in a rule is a regular expression: every character but `*` is matched literally, so a
 * rule cannot be malformed — a blank one is simply dropped.
 */
class KeepList(
    rules: List<String>,
    private val nsSeparator: String,
    private val keySeparator: String,
    private val defaultNamespaces: List<String>,
) {

    private val patterns: List<Pattern> = rules.map { it.trim() }.filter { it.isNotEmpty() }.map(::compile)

    fun isEmpty(): Boolean = patterns.isEmpty()

    /** True when [key] — `ns:path` or a bare `path` — matches one of the rules. */
    fun matches(key: String): Boolean {
        if (patterns.isEmpty()) return false
        val (namespace, path) = split(key)
        return patterns.any { pattern ->
            val namespaceMatches = when {
                pattern.namespace == null -> true
                namespace == null -> pattern.namespace in defaultNamespaces
                else -> pattern.namespace == namespace
            }
            namespaceMatches && pattern.path.matches(path)
        }
    }

    private fun compile(rule: String): Pattern {
        val (namespace, path) = split(rule)
        val glob = if (keySeparator.isNotEmpty() && path.endsWith(keySeparator)) "$path*" else path
        val regex = glob.split("*").joinToString(".*") { Regex.escape(it) }
        return Pattern(namespace, Regex(regex))
    }

    private fun split(text: String): Pair<String?, String> {
        if (nsSeparator.isEmpty() || !text.contains(nsSeparator)) return null to text
        return text.substringBefore(nsSeparator) to text.substringAfter(nsSeparator)
    }

    private class Pattern(val namespace: String?, val path: Regex)

    companion object {

        /** What separates two rules in the settings field. */
        private val RULE_SEPARATOR = Regex("[,;\\n]")

        fun parse(text: String): List<String> = text.split(RULE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

        fun of(config: Config): KeepList =
            KeepList(parse(config.keptKeys), config.nsSeparator, config.keySeparator, config.defaultNamespaces())
    }
}
