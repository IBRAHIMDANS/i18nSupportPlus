package utils

import com.ibrahimdans.i18n.plugin.key.lexer.Literal

/**
 * Base class and utils for unit tests
 */
interface TestBase {

    /**
     * Converts vararg string to list of literals
     */
    fun literalsList(vararg text: String): List<Literal> = text.toList().map {item -> Literal(item)}
}