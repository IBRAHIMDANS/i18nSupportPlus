package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec.encode
import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec.parse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XliffTranslationCodecTest {

    private val translations = mapOf(
        "common:menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
        "common:menu.exit" to mapOf("en" to "Exit", "fr" to ""),
        "common:terms" to mapOf("en" to "See <b>the terms</b> & conditions", "fr" to "Voir <b>les CGU</b> & co"),
        "common:item_one" to mapOf("en" to "{{count}} item", "fr" to "{{count}} article"),
        "common:item_other" to mapOf("en" to "{{count}} items", "fr" to "{{count}} articles"),
        "common:only_fr" to mapOf("fr" to "Seulement"),
    )

    @Test
    fun exportThenParseRoundTripsWithoutLoss() {
        val records = parse(encode("en", "fr", translations))

        assertEquals(listOf("key", "fr"), records.first())
        val rows = records.drop(1).associate { it[0] to it[1] }
        assertEquals("Accueil", rows["common:menu.home"])
        assertEquals("Voir <b>les CGU</b> & co", rows["common:terms"])
        assertEquals("{{count}} article", rows["common:item_one"], "a plural form is a unit of its own")
        assertEquals("{{count}} articles", rows["common:item_other"])
        assertEquals("", rows["common:menu.exit"])
    }

    @Test
    fun markupInAValueIsEscapedText() {
        val xliff = encode("en", "fr", translations)
        assertTrue(xliff.contains("See &lt;b&gt;the terms&lt;/b&gt; &amp; conditions"), xliff)
        assertFalse(xliff.contains("<b>"), "a value's tags must never become XML markup")
    }

    @Test
    fun anEmptyTargetNeedsTranslation() {
        val xliff = encode("en", "fr", translations)
        val unit = xliff.substringAfter("""id="common:menu.exit">""").substringBefore("</trans-unit>")
        assertTrue(unit.contains("""state="needs-translation""""), unit)
    }

    @Test
    fun aKeyWithoutReferenceValueIsLeftOut() {
        assertFalse(encode("en", "fr", translations).contains("common:only_fr"))
    }

    /** What a CAT tool sends back: extra attributes, notes, inline markup inside the target. */
    @Test
    fun aForeignXliffIsRead() {
        val foreign = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:sdl="http://sdl.com/FileTypes/SdlXliff/1.0">
              <file original="x" source-language="en" target-language="de" datatype="plaintext" sdl:tool="Trados">
                <header><note>exported by a tool</note></header>
                <body>
                  <trans-unit id="common:menu.home" approved="yes" sdl:locked="false">
                    <source>Home</source>
                    <target state="translated">Start<g id="1">seite</g></target>
                    <note>reviewed</note>
                  </trans-unit>
                  <trans-unit id="common:menu.exit"><source>Exit</source></trans-unit>
                </body>
              </file>
            </xliff>
        """.trimIndent()

        val records = parse(foreign)
        assertEquals(listOf("key", "de"), records.first())
        assertEquals(listOf("common:menu.home", "Startseite"), records[1])
        assertEquals(listOf("common:menu.exit", ""), records[2])
    }

    @Test
    fun externalEntitiesAreRefused() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE xliff [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <xliff version="1.2"><file target-language="fr"><body>
              <trans-unit id="k"><source>a</source><target>&secret;</target></trans-unit>
            </body></file></xliff>
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { parse(xxe) }
    }

    @Test
    fun malformedXmlOrNoUnitIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { parse("<xliff><file") }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""<xliff version="1.2"><file target-language="fr"><body/></file></xliff>""")
        }
    }
}
