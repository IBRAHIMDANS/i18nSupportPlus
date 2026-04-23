package com.ibrahimdans.i18n.plugin.utils.generator.translation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PoTranslationGeneratorTest {

    private val gen = PoTranslationGenerator()

    @Test
    fun testExt() {
        assertEquals("po", gen.ext())
    }

    @Test
    fun testGenerateContent_containsMsgid() {
        val result = gen.generateContent("section", "key", "value")
        assertTrue(result.contains("msgid \"section.key\""), "Expected msgid with merged key")
        assertTrue(result.contains("msgstr \"value\""))
    }

    @Test
    fun testGenerate_singleBranch() {
        val result = gen.generate("root", arrayOf("sub", "hello"))
        assertTrue(result.contains("msgid \"root.sub\""))
        assertTrue(result.contains("msgstr \"hello\""))
    }

    @Test
    fun testGenerate_multipleBranches() {
        val result = gen.generate("ns", arrayOf("a", "val1"), arrayOf("b", "val2"))
        assertTrue(result.contains("msgid \"ns.a\""))
        assertTrue(result.contains("msgid \"ns.b\""))
        assertTrue(result.contains("msgstr \"val1\""))
        assertTrue(result.contains("msgstr \"val2\""))
    }

    @Test
    fun testGenerateNamedBlock_isNoOp() {
        val block = "msgid \"k\"\nmsgstr \"v\"\n"
        assertEquals(block, gen.generateNamedBlock("ignored", block, 0))
        assertEquals(block, gen.generateNamedBlock("ignored", block, 2))
    }

    @Test
    fun testGeneratePlural_containsAllForms() {
        val result = gen.generatePlural("ns", "count", "items", "one", "few", "many")
        assertTrue(result.contains("msgid_plural"))
        assertTrue(result.contains("msgstr[0]"))
        assertTrue(result.contains("msgstr[1]"))
        assertTrue(result.contains("msgstr[2]"))
    }

    @Test
    fun testGenerateInvalidRoot_isEmpty() {
        assertEquals("", gen.generateInvalidRoot())
    }
}
