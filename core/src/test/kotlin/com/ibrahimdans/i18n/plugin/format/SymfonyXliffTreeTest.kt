package com.ibrahimdans.i18n.plugin.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A Symfony XLIFF catalogue, keyed as Symfony keys it. */
class SymfonyXliffTreeTest {

    private fun values(text: String, keying: XliffKeying = XliffKeying.SYMFONY): List<Pair<String, String?>> =
        SymfonyXliffTree.parse(text, keying)!!.entries().map { (key, node) -> key to node.value().value }

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

    private val angular12 = """
        <xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" version="1.2">
          <file source-language="en-US" target-language="fr" datatype="plaintext" original="ng2.template">
            <body>
              <trans-unit id="home.title" datatype="html"><source>Welcome</source><target>Bienvenue</target></trans-unit>
              <trans-unit id="cart.terms" datatype="html"><source>Terms apply.</source></trans-unit>
              <trans-unit id="cart.saved" datatype="html"><source>Saved</source><target/></trans-unit>
            </body>
          </file>
        </xliff>
    """.trimIndent()

    @Test
    fun `Angular source file - keyed by id, valued by the source`() {
        assertEquals(
            listOf("home.title" to "Welcome", "cart.terms" to "Terms apply.", "cart.saved" to "Saved"),
            values(angular12, XliffKeying.ANGULAR_SOURCE),
        )
    }

    @Test
    fun `Angular locale file - a unit with no target is no key, an empty target is an empty value`() {
        assertEquals(listOf("home.title" to "Bienvenue", "cart.saved" to ""), values(angular12, XliffKeying.ANGULAR_TARGET))
    }

    @Test
    fun `Angular XLIFF 2·0 - keyed by the unit id`() {
        val text = """
            <xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en-US" trgLang="fr">
              <file id="ngi18n" original="ng.template">
                <unit id="home.title"><segment><source>Welcome</source><target>Bienvenue</target></segment></unit>
                <unit id="home.intro"><segment><source>Hello</source></segment></unit>
              </file>
            </xliff>
        """.trimIndent()
        assertEquals(listOf("home.title" to "Bienvenue"), values(text, XliffKeying.ANGULAR_TARGET))
        assertEquals(listOf("home.title" to "Welcome", "home.intro" to "Hello"), values(text, XliffKeying.ANGULAR_SOURCE))
    }

    @Test
    fun `not XLIFF, or not XML, is no tree`() {
        assertNull(SymfonyXliffTree.parse("<html><body/></html>"))
        assertNull(SymfonyXliffTree.parse("<xliff><file>"))
        assertNull(SymfonyXliffTree.parse("user: name"))
    }
}
