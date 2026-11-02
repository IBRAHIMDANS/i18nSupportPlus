package com.ibrahimdans.i18n.plugin.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a Symfony translation file's name says: `<domain>[+intl-icu].<locale>.<format>`. */
class SymfonyCatalogueTest {

    @Test
    fun `the name gives the domain and the locale`() {
        assertEquals(SymfonyCatalogue("messages", "fr"), SymfonyCatalogue.parse("messages.fr.yaml"))
        assertEquals(SymfonyCatalogue("validators", "pt_BR"), SymfonyCatalogue.parse("validators.pt_BR.xlf"))
    }

    @Test
    fun `an ICU catalogue holds the same domain`() {
        assertEquals(SymfonyCatalogue("messages", "de"), SymfonyCatalogue.parse("messages+intl-icu.de.yaml"))
    }

    @Test
    fun `a name without domain locale and format is no catalogue`() {
        assertNull(SymfonyCatalogue.parse("messages.yaml"))
        assertNull(SymfonyCatalogue.parse(".fr.yaml"))
        assertNull(SymfonyCatalogue.parse("+intl-icu.fr.yaml"))
    }
}
