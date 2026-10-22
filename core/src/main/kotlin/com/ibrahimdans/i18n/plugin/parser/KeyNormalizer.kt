package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType

/**
 * Key normalization utils interface
 */
interface KeyNormalizer {

    /**
     * Normalizes single key element
     */
    fun normalize(element: KeyElement): KeyElement? = element
}

/**
 * Normalizers element with template expression
 */
class ExpressionNormalizer: KeyNormalizer {
    private val dropItems = listOf("`", "{", "}", "$")
    override fun normalize(element: KeyElement): KeyElement? = when {
        dropItems.contains(element.text) -> null
        element.type == KeyElementType.TEMPLATE -> element.copy(text="\${${element.text}}")
        else -> element
    }
}

/**
 * Normalizes dummy key, used in key completion: replaces [dummy], the identifier the IDE inserts at
 * the caret, by its [trimmed] form.
 */
class DummyTextNormalizer(private val dummy: String, private val trimmed: String): KeyNormalizer {
    override fun normalize(element: KeyElement): KeyElement? {
        return element.copy(text = element.text.replace(dummy, trimmed))
    }
}
