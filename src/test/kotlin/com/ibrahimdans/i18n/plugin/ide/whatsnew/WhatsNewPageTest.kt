package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewPage.Section
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WhatsNewPageTest {

    private val sections = listOf("1.10.0", "1.9.0", "1.8.0", "1.7.0", "1.6.0")
        .map { Section(it, "<h2>$it</h2><ul><li>change of $it</li></ul>") }

    private fun versions(previous: String?, current: String) =
        WhatsNewPage.select(sections, previous, current).map { it.version }

    @Test
    fun `the same version shows the current one only`() {
        assertEquals(listOf("1.9.0"), versions("1.9.0", "1.9.0"))
    }

    @Test
    fun `a direct update shows the current version only`() {
        assertEquals(listOf("1.10.0"), versions("1.9.0", "1.10.0"))
    }

    @Test
    fun `one skipped version is shown under the current one`() {
        assertEquals(listOf("1.10.0", "1.9.0"), versions("1.8.0", "1.10.0"))
    }

    @Test
    fun `several skipped versions are shown newest first`() {
        assertEquals(listOf("1.10.0", "1.9.0", "1.8.0", "1.7.0"), versions("1.6.0", "1.10.0"))
    }

    @Test
    fun `a first install or an unknown previous version shows the current one only`() {
        assertEquals(listOf("1.9.0"), versions(null, "1.9.0"))
        assertEquals(listOf("1.9.0"), versions("", "1.9.0"))
        assertEquals(listOf("1.9.0"), versions("dev", "1.9.0"))
    }

    @Test
    fun `a previous version missing from the changelog still bounds the range`() {
        assertEquals(listOf("1.10.0", "1.9.0", "1.8.0"), versions("1.7.5", "1.10.0"))
        assertEquals(listOf("1.10.0", "1.9.0", "1.8.0", "1.7.0", "1.6.0"), versions("1.0.0", "1.10.0"))
    }

    @Test
    fun `a downgrade shows the current version only`() {
        assertEquals(listOf("1.8.0"), versions("1.10.0", "1.8.0"))
    }

    @Test
    fun `a current version absent from the changelog has no page`() {
        assertEquals(emptyList<String>(), versions("1.9.0", "1.11.0"))
        assertNull(WhatsNewPage.build(sections, "1.9.0", "1.11.0", "Title", "Full"))
    }

    @Test
    fun `the page carries the title, the sections and the changelog link`() {
        val html = WhatsNewPage.build(sections, "1.8.0", "1.10.0", "What's New <1.10.0>", "Full changelog")!!
        assertTrue("<h1>What's New &lt;1.10.0&gt;</h1>" in html, html)
        assertTrue("<section data-version=\"1.10.0\">" in html)
        assertTrue("<section data-version=\"1.9.0\">" in html)
        assertFalse("change of 1.8.0" in html)
        assertTrue(html.indexOf("change of 1.10.0") < html.indexOf("change of 1.9.0"))
        assertTrue("href=\"${WhatsNewPage.CHANGELOG_URL}\">Full changelog</a>" in html)
        assertTrue("script-src 'none'" in html)
    }

    @Test
    fun `scripts and inline handlers are dropped from the notes`() {
        val notes = listOf(Section("1.0.0", "<p onclick=\"alert(1)\">a</p><SCRIPT>alert(2)</script >b"))
        val html = WhatsNewPage.build(notes, null, "1.0.0", "T", "F")!!
        assertFalse("alert" in html, html)
        assertTrue("<p>a</p>b" in html, html)
    }

    @Test
    fun `the resource is split on its version markers`() {
        val parsed = WhatsNewPage.parse(
            "<!-- whats-new-version: 1.2.0 -->\n<h2>1.2.0</h2>\n<!-- whats-new-version: 1.1.0 -->\n<h2>1.1.0</h2>\n"
        )
        assertEquals(listOf(Section("1.2.0", "<h2>1.2.0</h2>"), Section("1.1.0", "<h2>1.1.0</h2>")), parsed)
    }

    @Test
    fun `the build embeds the change notes of every released version`() {
        val embedded = WhatsNewPage.sections
        assertNotNull(embedded, "whats-new.html is missing from the test classpath")
        assertTrue(embedded!!.size > 1, "expected several versions, got ${embedded.map { it.version }}")
        embedded.forEach { section ->
            assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(section.version), "unexpected version: ${section.version}")
            assertTrue("<h2>${section.version}" in section.html, "section ${section.version} has no heading")
            assertTrue("<li>" in section.html, "section ${section.version} has no entry")
        }
        assertEquals(embedded.map { it.version }.distinct(), embedded.map { it.version })
    }
}
