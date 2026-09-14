package com.ibrahimdans.i18n.plugin.key

import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FullKeyTest {

    private fun key(vararg segments: String) =
        FullKey(source = segments.joinToString("."), ns = null, compositeKey = segments.map { Literal(it) })

    @Test
    fun `a key is dynamic when a segment is a template expression or a wildcard`() {
        assertTrue(key("status", "\${kind}").isDynamic)
        assertTrue(key("\${ns}", "title").isDynamic)
        assertTrue(key("a", "*", "b").isDynamic)
    }

    @Test
    fun `a key made of plain segments is not`() {
        assertFalse(key("status", "pending").isDynamic)
        assertFalse(key("price\$", "amount").isDynamic, "a dollar in a name is not an expression")
        assertFalse(key("{braces}").isDynamic)
    }
}
