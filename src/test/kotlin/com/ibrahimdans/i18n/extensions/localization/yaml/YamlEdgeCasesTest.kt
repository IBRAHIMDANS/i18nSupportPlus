package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YamlEdgeCasesTest : PlatformBaseTest() {

    private val tg = YamlTranslationGenerator()
    private val cg = JsCodeGenerator()

    // ── Case 1: null value ────────────────────────────────────────────────────

    @Test
    fun testNullValueDoesNotCrash() {
        // key: (no value) — YAMLKeyValue.value is null
        // The reference assistant must not throw and must return empty references
        myFixture.configureByText("nullvalue.${tg.ext()}", "section:\n  nul<caret>lKey:\n")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertTrue(element!!.references.isEmpty())
    }

    // ── Case 2: multi-line values ─────────────────────────────────────────────

    @Test
    fun testBlockScalarLiteralValue() {
        // Block scalar with | — value spans multiple lines but key must still resolve
        val jsKey = "'multiLine:section.blockKey'"
        myFixture.configureByText("multiLine.${cg.ext()}", cg.generate(jsKey))
        myFixture.configureByText(
            "multiLine.${tg.ext()}",
            "section:\n  block<caret>Key: |\n    line one\n    line two\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertEquals(jsKey.unQuote(), element!!.references[0].resolve()?.text?.unQuote())
    }

    @Test
    fun testBlockScalarFoldedValue() {
        // Folded scalar with > — same expectation as |
        val jsKey = "'folded:section.foldedKey'"
        myFixture.configureByText("folded.${cg.ext()}", cg.generate(jsKey))
        myFixture.configureByText(
            "folded.${tg.ext()}",
            "section:\n  folded<caret>Key: >\n    a long sentence\n    continued here\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertEquals(jsKey.unQuote(), element!!.references[0].resolve()?.text?.unQuote())
    }

    // ── Case 3: keys with special characters ──────────────────────────────────

    @Test
    fun testKeyWithAtSign() {
        // @ is valid in unquoted YAML keys. The plugin must not crash.
        myFixture.configureByText(
            "atkey.${tg.ext()}",
            "section:\n  \"@<caret>key\": value\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        // References may be empty if the key composer cannot search for @-prefixed strings
        assertDoesNotThrow { element!!.references }
    }

    @Test
    fun testKeyWithDollarSign() {
        // $ is valid in YAML keys but may confuse the key search indexer
        myFixture.configureByText(
            "dollarkey.${tg.ext()}",
            "section:\n  \"\$<caret>key\": value\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertDoesNotThrow { element!!.references }
    }

    @Test
    fun testKeyWithDotInName() {
        // A quoted YAML key containing "." conflicts with the default keySeparator.
        // KNOWN ISSUE: the key composer interprets "key.with.dots" as nested path
        // segments, so the composed search key does not match the literal YAML key.
        // References will be empty until a dedicated quoting strategy is implemented.
        myFixture.configureByText(
            "dotkey.${tg.ext()}",
            "section:\n  \"key.with<caret>.dots\": value\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertDoesNotThrow { element!!.references }
    }

    @Test
    fun testKeyWithHashInName() {
        // # inside a quoted YAML key — outside quotes it would start a comment
        myFixture.configureByText(
            "hashkey.${tg.ext()}",
            "section:\n  \"key<caret>#name\": value\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertDoesNotThrow { element!!.references }
    }

    // ── Case 4: inline comments ───────────────────────────────────────────────

    @Test
    fun testInlineCommentDoesNotCorruptKeyReference() {
        // Inline YAML comment after the value — the YAML PSI strips it from the value
        // text, so key navigation to code usages must work as if the comment were absent
        val jsKey = "'commented:section.commentedKey'"
        myFixture.configureByText("commented.${cg.ext()}", cg.generate(jsKey))
        myFixture.configureByText(
            "commented.${tg.ext()}",
            "section:\n  commented<caret>Key: hello world # this is a comment\n"
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertEquals(jsKey.unQuote(), element!!.references[0].resolve()?.text?.unQuote())
    }
}
