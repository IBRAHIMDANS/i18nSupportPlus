package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class ExpressionKeyParserTest : ParserTestBase {

//fileName:ROOT.Key2.Key3                   /                       / fileName{8}:ROOT{4}.Key2{4}.Key3{4}
    @Test
    fun parseSimpleLiteral() {
        val elements = listOf(
            KeyElement.literal("fileName:ROOT.Key2.Key3")
        )
        val parsed = parse(elements)
        assertEquals("fileName{8}:ROOT{4}.Key2{4}.Key3{4}", toTestString(parsed))
        assertEquals("fileName:ROOT.Key2.Key3", parsed?.source)
    }

//fileName:ROOT.Key2.Key3.  — trailing separator is malformed → null
    @Test
    fun parseSimpleLiteral2() {
        val elements = listOf(
            KeyElement.literal("fileName:ROOT.Key2.Key3.")
        )
        assertNull(parse(elements))
    }

    @Test
    fun parseSimpleLiteralList() {
        val elements = listOf(
            KeyElement.literal("file"),
            KeyElement.literal("Name:ROOT.Key3.Key4.")
        )
        assertNull(parse(elements))
    }

    @Test
    fun parseFirstComponentNamespace() {
        val elements = listOf(
            KeyElement.literal("file"),
            KeyElement.literal("Name.ROOT.Key3.Key4.")
        )
        assertNull(parse(elements, emptyNamespace = true, firstComponentNamespace = true))
    }
}
