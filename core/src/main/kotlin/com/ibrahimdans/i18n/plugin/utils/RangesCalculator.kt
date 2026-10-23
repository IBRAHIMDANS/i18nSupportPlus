package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import kotlin.math.max

/**
 * Annotation utils
 */
interface RangesCalculator {
    /**
     * Calculates bounds of composite key
     */
    fun compositeKeyFullBounds(fullKey: FullKey): TextSpan

    /**
     * Calculates bounds of unresolved part of key
     */
    fun unresolvedKey(fullKey: FullKey, resolvedPath: List<Literal>): TextSpan

    /**
     * Calculates bounds of unresolved namespace
     */
    fun unresolvedNs(fullKey: FullKey): TextSpan
}

/**
 * Annotation utils implementation, for a key written in the literal at [textSpan]
 */
class KeyRangesCalculator(private val textSpan: TextSpan, isQuoted: Boolean = true) : RangesCalculator {

    private val quoteOffset = if (isQuoted) 1 else 0
    private val nsSeparatorOffset = 1
    private val keySeparatorOffset = 1

    override fun unresolvedNs(fullKey: FullKey): TextSpan =
        safeRange(
            textSpan.start + quoteOffset,
            textSpan.start + quoteOffset + (fullKey.ns?.length ?: 0)
        )

    override fun unresolvedKey(fullKey: FullKey, resolvedPath: List<Literal>): TextSpan {
        // A hook's key prefix leads the resolved path but is not written in the literal: only
        // the segments after it have a position in the text. When resolution stopped inside the
        // prefix itself, nothing written resolved, so the whole key is what is unresolved.
        if (resolvedPath.size < fullKey.keyPrefix.size) return compositeKeyFullBounds(fullKey)
        val writtenPath = resolvedPath.drop(fullKey.keyPrefix.size)
        return safeRange(
            fullKey.compositeKeyStartOffset() + tokensLength(writtenPath) + (if (writtenPath.isNotEmpty()) keySeparatorOffset else 0),
            fullKey.compositeKeyEndOffset()
        )
    }

    override fun compositeKeyFullBounds(fullKey: FullKey) =
        safeRange(
            fullKey.compositeKeyStartOffset(),
            fullKey.compositeKeyEndOffset()
        )

    private fun FullKey.compositeKeyStartOffset(): Int =
        textSpan.start + (this.ns?.let { it.length + nsSeparatorOffset } ?: 0) + quoteOffset

    private fun FullKey.compositeKeyEndOffset(): Int =
        textSpan.start + quoteOffset + this.source.length

    private fun safeRange(start: Int, end: Int): TextSpan = TextSpan(start, max(end, start))

    private fun tokensLength(tokens: List<Literal>):Int {
        val lengths = tokens.map { n -> n.length}.filter { v -> v > 0}
        val dotCorrection = 0//tokens.map { n -> n.dot}.sum()
        return lengths.sum() + (if (lengths.isEmpty()) 0 else (lengths.size - 1)) - dotCorrection
    }
}