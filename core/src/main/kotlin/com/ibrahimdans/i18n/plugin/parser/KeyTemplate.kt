package com.ibrahimdans.i18n.plugin.parser

/** How a module's key template says the keys of its code are written. */
sealed interface KeySyntax {
    /** `{ns}:{key}`, `{ns}.{key}`: the namespace leads, followed by the one-character [nsSeparator]. */
    data class Namespaced(val nsSeparator: String) : KeySyntax

    /** `{key}`: keys carry no namespace. */
    data object NoNamespace : KeySyntax
}

/**
 * Reads a module's `keyTemplate`. Pure, so the accepted forms are testable without a project.
 *
 * The template was edited in the settings and read by nothing: a module whose code writes
 * `common.title` still had it parsed with the global namespace separator.
 */
object KeyTemplate {

    // One character, like the project's own separators: the key tokenizer splits on single characters.
    private val NAMESPACED = Regex("""\{\s*(?:ns|namespace)\s*}([^{}\s\w])\{\s*key\s*}""", RegexOption.IGNORE_CASE)
    private val KEY_ONLY = Regex("""\{\s*key\s*}""", RegexOption.IGNORE_CASE)

    /** The syntax [template] states, or null when it is blank or not one of the accepted forms. */
    fun parse(template: String): KeySyntax? {
        val trimmed = template.trim()
        if (KEY_ONLY.matches(trimmed)) return KeySyntax.NoNamespace
        return NAMESPACED.matchEntire(trimmed)?.let { KeySyntax.Namespaced(it.groupValues[1]) }
    }
}
