package com.ibrahimdans.i18n.plugin.key.parser

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.*
import com.ibrahimdans.i18n.plugin.parser.RawKey

data class CompositeKey(val ns: Literal?, val key: List<Literal>)

/**
 * Parses list of normalized key elements into FullKey
 */
class KeyParser(private val tokenizer: Tokenizer) {

    /**
     * Parses list of key elements into i18n key
     */
    fun parse(
        rawKey: RawKey,
        emptyNamespace: Boolean = false,
        firstComponentNamespace: Boolean = false
    ): FullKey? {
        val startState = if (emptyNamespace) {
            if (firstComponentNamespace) {
                Start(null, KeySeparator)
            }
            else {
                WaitingLiteral(file = null, key = emptyList())
            }
        } else {
            Start(null)
        }
        val (source, tokenized) = tokenizer.tokenize(rawKey.keyElements)
        val (prefixSource, prefix) = prefixOf(rawKey)
        return tokenized
            .fold(startState) { state, token -> state.next(token) }
            .fullKey()?.let {
                // The prefix leads the composite key, which is what resolution walks, while
                // `source` stays the text the literal writes: ranges and references are computed
                // against that text, where the prefix does not appear.
                (ns, key) -> FullKey(source, ns, prefix + key, rawKey.arguments, prefix, prefixSource)
            }
    }

    /**
     * The literals of [RawKey.keyPrefix], split by the same tokenizer as the key so they follow the
     * configured key separator. A namespace separator written in a prefix has no meaning there —
     * the hook's namespace is given apart — so separators of either kind only delimit segments.
     */
    private fun prefixOf(rawKey: RawKey): Pair<String?, List<Literal>> {
        if (rawKey.keyPrefix.isEmpty()) return null to emptyList()
        val (text, tokens) = tokenizer.tokenize(rawKey.keyPrefix)
        return text to tokens.filterIsInstance<Literal>().filter { it.text.isNotEmpty() }
    }
}

/**
 * Parsing state machine's state
 */
private sealed interface State {
    /**
     * Process next token
     */
    fun next(token: Token): State

    /**
     * Get current parsed key
     */
    fun fullKey(): CompositeKey? = null
}

/**
 * Final error state
 */
private data class Error(val msg: String): State {
    override fun next(token: Token): State = this
}

/**
 * Initial state
 */
private class Start(private val init: Literal?, private val nsSeparator: Separator = NsSeparator) : State {
    override fun next(token: Token): State =
        when {
            token == nsSeparator && init != null  -> WaitingLiteral(init, listOf())
            token is KeySeparator && init != null -> WaitingLiteral(null, listOf(init))
            token is Literal -> Start(init?.merge(token) ?: token, nsSeparator)
            else -> Error("Invalid ns separator position (0)") // Never get here
        }
    override fun fullKey(): CompositeKey? = init?.let {CompositeKey(null, listOf(it))}
}

/**
 * Waiting literal state
 */
private class WaitingLiteral(private val file: Literal?, val key: List<Literal>) : State {
    override fun next(token: Token): State =
        when (token) {
            is Literal -> WaitingLiteralOrSeparator(file, key + token)
            is Separator -> Error("Invalid token $token")
        }
    override fun fullKey(): CompositeKey? = null
}

/**
 * Waiting literal or separator
 */
private class WaitingLiteralOrSeparator(val file: Literal?, val key: List<Literal>) : State {
    override fun next(token: Token): State =
        when (token) {
            is Literal -> WaitingLiteralOrSeparator(file, key.dropLast(1) + key.last().merge(token))
            is KeySeparator -> WaitingLiteral(file, key)
            is NsSeparator -> Error("Invalid token $token")
        }
    override fun fullKey(): CompositeKey = CompositeKey(file, key)
}
