package com.ibrahimdans.i18n.plugin.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A Symfony XLIFF catalogue, keyed as Symfony keys it. */
class SymfonyXliffTreeTest {

    private fun values(text: String): List<Pair<String, String?>> =
        SymfonyXliffTree.parse(text)!!.entries().map { (key, node) -> key to node.value().value }

    @Test
    fun `XLIFF 1·2 - keyed by resname, else by the source text, valued by the target, else the source`() {
        val text = """
            <?xml version="1.0" encoding="utf-8"?>
            <xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" version="1.2">
              <file source-language="en" target-language="fr" datatype="plaintext" original="file.ext">
                <body>
                  <group id="g">
                    <trans-unit id="a1" resname="contact.email.invalid">
                      <source>contact.email.invalid</source>
                      <target>Adresse <g id="1">invalide</g></target>
                    </trans-unit>
                  </group>
                  <trans-unit id="a2">
                    <source>This value is too short.</source>
                  </trans-unit>
                  <trans-unit id="a3" resname="empty">
                    <source>empty</source>
                    <target/>
                  </trans-unit>
                  <trans-unit id="a4" resname="contact.email.invalid">
                    <target>Duplicate</target>
                  </trans-unit>
                  <trans-unit id="a5"/>
                </body>
              </file>
            </xliff>
        """.trimIndent()
        assertEquals(
            listOf("contact.email.invalid" to "Adresse invalide", "This value is too short." to "This value is too short.", "empty" to ""),
            values(text),
        )
    }

    @Test
    fun `XLIFF 2·0 - keyed by name, the texts of the first segment`() {
        val text = """
            <xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="fr">
              <file id="messages.fr">
                <unit id="u1" name="user.name">
                  <segment><source>user.name</source><target>Nom</target></segment>
                  <segment><source>other</source><target>Autre</target></segment>
                </unit>
                <unit id="u2">
                  <segment><source>Hello</source></segment>
                </unit>
              </file>
            </xliff>
        """.trimIndent()
        assertEquals(listOf("user.name" to "Nom", "Hello" to "Hello"), values(text))
    }

    @Test
    fun `not XLIFF, or not XML, is no tree`() {
        assertNull(SymfonyXliffTree.parse("<html><body/></html>"))
        assertNull(SymfonyXliffTree.parse("<xliff><file>"))
        assertNull(SymfonyXliffTree.parse("user: name"))
    }
}
