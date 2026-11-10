package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [isQuoted] and [unQuote], which every key read from a literal goes through. */
internal class StringUtilsTest {

    @Test
    fun isQuoted() {
        listOf("'", "`", "\"").forEach {
            assertFalse("${it}sub".isQuoted())
            assertFalse("sub${it}'".isQuoted())
            assertTrue("${it}subr${it}".isQuoted())
        }
        assertFalse("u".isQuoted())
    }

    @Test
    fun unQuote() {
        listOf("'", "`", "\"").forEach {
            val left = "${it}subs"
            assertEquals(left.unQuote(), left)
            val right = "subs${it}"
            assertEquals(right.unQuote(), right)
            val wrapped = "${it}subs${it}"
            assertEquals(wrapped.unQuote(), "subs")
        }
    }

    @Test
    fun doubleUnQuote() {
        listOf("'", "`", "\"").forEach {
            val wrapped = "\"${it}'`subs`'${it}\""
            assertEquals(wrapped.unQuote(), "${it}'`subs`'${it}")
        }
    }
}
