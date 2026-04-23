package com.ibrahimdans.i18n.extensions.localization.po

import com.ibrahimdans.i18n.extensions.localization.plain.`object`.PlainObjectContentGenerator
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlainObjectContentGeneratorTest {

    private val gen = PlainObjectContentGenerator()

    @Test
    fun testGenerateContent_simple() {
        val result = gen.generateContent(listOf(Literal("hello")), "world")
        assertEquals("msgid \"hello\"\nmsgstr \"world\"\n", result)
    }

    @Test
    fun testGenerateContent_compositeKey() {
        val result = gen.generateContent(listOf(Literal("section"), Literal("key")), "value")
        assertEquals("msgid \"section.key\"\nmsgstr \"value\"\n", result)
    }

    @Test
    fun testGenerateContent_escapesBackslash() {
        val result = gen.generateContent(listOf(Literal("k")), "path\\to\\file")
        assertEquals("msgid \"k\"\nmsgstr \"path\\\\to\\\\file\"\n", result)
    }

    @Test
    fun testGenerateContent_escapesQuote() {
        val result = gen.generateContent(listOf(Literal("k")), "say \"hello\"")
        assertEquals("msgid \"k\"\nmsgstr \"say \\\"hello\\\"\"\n", result)
    }

    @Test
    fun testGenerateContent_escapesNewline() {
        val result = gen.generateContent(listOf(Literal("k")), "line1\nline2")
        assertEquals("msgid \"k\"\nmsgstr \"line1\\nline2\"\n", result)
    }

    @Test
    fun testGenerateContent_emptyValue() {
        val result = gen.generateContent(listOf(Literal("untranslated")), "")
        assertEquals("msgid \"untranslated\"\nmsgstr \"\"\n", result)
    }

    @Test
    fun testGenerateContent_escapesKeyQuote() {
        val result = gen.generateContent(listOf(Literal("say \"hi\"")), "hello")
        assertEquals("msgid \"say \\\"hi\\\"\"\nmsgstr \"hello\"\n", result)
    }
}
