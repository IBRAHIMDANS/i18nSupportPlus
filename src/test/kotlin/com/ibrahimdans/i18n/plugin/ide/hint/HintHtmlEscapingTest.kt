package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The hint popup is built by concatenating raw HTML, so any translation carrying markup,
 * an entity or a comparison used to be interpreted as markup instead of being displayed.
 */
class HintHtmlEscapingTest : PlatformBaseTest() {

    private fun hintFor(ns: String, value: String): String? {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/$ns.${tg.ext()}", tg.generateContent("root", "first", "second", value))
        myFixture.configureByText("content_$ns.js", JsCodeGenerator().generate("\"$ns:root.first.<caret>second\"", 0))
        var hint: String? = null
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            hint = HintProvider().generateDoc(null, codeElement)
        }
        return hint
    }

    @Test
    fun testMarkupInTranslationIsEscaped() {
        val hint = hintFor("markup", "Voir <b>les CGU</b>")
        assertNotNull(hint)
        assertTrue("The tag must be escaped, not emitted as markup", hint!!.contains("&lt;b&gt;"))
        assertFalse("A raw <b> would be interpreted by the popup renderer", hint.contains("Voir <b>"))
    }

    @Test
    fun testAmpersandIsEscaped() {
        val hint = hintFor("amp", "Terms & conditions")
        assertNotNull(hint)
        assertTrue("The ampersand must be escaped", hint!!.contains("Terms &amp; conditions"))
    }

    @Test
    fun testEntityInTranslationIsNotInterpreted() {
        val hint = hintFor("entity", "Prix&nbsp;: 10")
        assertNotNull(hint)
        assertTrue("The entity must be shown literally", hint!!.contains("Prix&amp;nbsp;: 10"))
    }

    @Test
    fun testComparisonIsEscaped() {
        val hint = hintFor("cmp", "a < b")
        assertNotNull(hint)
        assertTrue("The comparison must be escaped", hint!!.contains("a &lt; b"))
    }

    @Test
    fun testQuotesAreEscaped() {
        val hint = hintFor("quotes", """He said "hi" and it's fine""")
        assertNotNull(hint)
        assertFalse("A raw double quote could close an attribute", hint!!.contains("\"hi\""))
        assertFalse("A raw apostrophe could close an attribute", hint.contains("it's"))
    }

    /** The common case must be byte-for-byte what it was before escaping was introduced. */
    @Test
    fun testPlainTranslationIsUnchanged() {
        val hint = hintFor("plain", "Bonjour tout le monde")
        assertNotNull(hint)
        assertTrue("A translation without special characters must pass through untouched",
            hint!!.contains("<td style='padding-left:8px; white-space:nowrap'>Bonjour tout le monde</td>"))
        assertFalse("No entity must be introduced", hint.contains("&amp;"))
    }

    /** The navigation link must stay a valid single-quoted attribute. */
    @Test
    fun testNavigationLinkStaysWellFormed() {
        val hint = hintFor("nav", "Hello")
        assertNotNull(hint)
        val href = Regex("href='([^']*)'").find(hint!!)
        assertNotNull("The href attribute must still be parseable", href)
        assertTrue("The link must still target the translation file",
            href!!.groupValues[1].startsWith("psi_element://"))
    }

    /**
     * Truncation must be computed on the text, not on the entities: escaping first would
     * let five characters of `&amp;` eat the budget of a single `&`.
     */
    @Test
    fun testTruncationIsComputedBeforeEscaping() {
        val hint = hintFor("trunc", "&".repeat(70))
        assertNotNull(hint)
        assertEquals("60 source characters must survive truncation, whatever their escaped length",
            60, Regex("&amp;").findAll(hint!!).count())
        assertTrue("The value must still be marked as truncated", hint.contains("..."))
    }
}
