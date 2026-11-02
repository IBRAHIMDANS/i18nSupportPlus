package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.format.PropertiesBundle.Placement
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class PropertiesBundleTest {

    @Test
    fun `the locale is the end of the name after the base`() {
        Assertions.assertEquals(PropertiesBundle.Name("messages", "fr"), PropertiesBundle.nameOf("messages_fr"))
        Assertions.assertEquals(PropertiesBundle.Name("messages", "pt_BR"), PropertiesBundle.nameOf("messages_pt_BR"))
        Assertions.assertEquals(PropertiesBundle.Name("my_app", "de"), PropertiesBundle.nameOf("my_app_de"))
        Assertions.assertEquals(PropertiesBundle.Name("messages", null), PropertiesBundle.nameOf("messages"))
    }

    @Test
    fun `the default file holds the reference locale, the base name no namespace in a folder of one bundle`() {
        Assertions.assertEquals(
            mapOf(
                "messages" to Placement("en", null),
                "messages_fr" to Placement("fr", null),
                "messages_pt_BR" to Placement("pt_BR", null),
            ),
            PropertiesBundle.placementsIn(listOf("messages", "messages_fr", "messages_pt_BR"), "en"),
        )
    }

    @Test
    fun `the default file is the fallback, left out, when the reference locale has its own file`() {
        Assertions.assertEquals(
            mapOf("messages_en" to Placement("en", null), "messages_fr" to Placement("fr", null)),
            PropertiesBundle.placementsIn(listOf("messages", "messages_en", "messages_fr"), "en"),
        )
    }

    @Test
    fun `several bundles in a folder are namespaces, a file with no localized sibling is no bundle`() {
        Assertions.assertEquals(
            mapOf(
                "messages" to Placement("en", "messages"),
                "messages_fr" to Placement("fr", "messages"),
                "errors" to Placement("en", "errors"),
                "errors_fr" to Placement("fr", "errors"),
            ),
            PropertiesBundle.placementsIn(listOf("messages", "messages_fr", "errors", "errors_fr", "application"), "en"),
        )
    }
}
