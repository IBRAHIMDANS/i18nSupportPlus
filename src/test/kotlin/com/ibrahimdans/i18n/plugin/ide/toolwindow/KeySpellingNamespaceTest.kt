package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How [KeySpelling] takes a namespace off a key: i18next's `ns:key`, Laravel's `package::file:key`. */
class KeySpellingNamespaceTest {

    @Test
    fun `an i18next namespace ends at the first colon`() {
        assertEquals("common", KeySpelling.namespaceOf("common:menu.home"))
        assertEquals("menu.home", KeySpelling.pathOf("common:menu.home"))
        assertEquals("common", KeySpelling.namespaceOf("common:a::b"))
        assertNull(KeySpelling.namespaceOf("menu.home"))
        assertNull(KeySpelling.namespaceOf(":menu"))
    }

    @Test
    fun `a Laravel package namespace keeps its double colon`() {
        assertEquals("courier::messages", KeySpelling.namespaceOf("courier::messages:welcome"))
        assertEquals("sent.ok", KeySpelling.pathOf("courier::messages:sent.ok"))
        assertEquals(listOf("sent", "ok"), KeySpelling.segmentsOf("courier::messages:sent.ok", Config()))
    }

    @Test
    fun `a double colon with no namespace separator after it is read as before`() {
        assertEquals("courier", KeySpelling.namespaceOf("courier::messages"))
        assertEquals("courier", KeySpelling.namespaceOf("courier:::x"))
    }
}
