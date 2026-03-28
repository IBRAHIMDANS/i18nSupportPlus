package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyRequestResultTest {

    @Test
    fun `equals and hashCode`() {
        val a = KeyRequestResult(null, true)
        val b = KeyRequestResult(null, true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy with modification`() {
        val original = KeyRequestResult(null, true)
        val copy = original.copy(isCancelled = false)
        assertFalse(copy.isCancelled)
        assertNull(copy.key)
    }

    @Test
    fun `toString contains field values`() {
        val result = KeyRequestResult(null, true)
        val str = result.toString()
        assertTrue(str.contains("isCancelled=true"))
    }

    @Test
    fun `destructuring`() {
        val result = KeyRequestResult(null, false)
        val (key, isCancelled) = result
        assertNull(key)
        assertFalse(isCancelled)
    }
}
