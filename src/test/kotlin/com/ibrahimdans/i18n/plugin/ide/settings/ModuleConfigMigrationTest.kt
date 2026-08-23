package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Guards the persisted shape of [ModuleConfig] against the addition of `referenceLocale`.
 *
 * The field is new, so every workspace that was configured before it existed has a stored
 * state without it. What must not happen is that such a state fails to load, or loads with
 * a surprising value.
 */
class ModuleConfigMigrationTest {

    @Test
    fun testLegacyStateWithoutReferenceLocaleLoadsWithEmptyDefault() {
        val serialized = XmlSerializer.serialize(
            ModuleConfig(
                name = "frontend",
                rootDirectory = "src/locales",
                pathTemplate = "{lang}/{ns}.json",
                referenceLocale = "en"
            )
        )
        // Emulate a state written before the field existed: the option is simply absent.
        removeOption(serialized, "referenceLocale")

        val loaded = XmlSerializer.deserialize(serialized, ModuleConfig::class.java)

        assertEquals("frontend", loaded.name)
        assertEquals("src/locales", loaded.rootDirectory)
        assertEquals("{lang}/{ns}.json", loaded.pathTemplate)
        assertEquals("", loaded.referenceLocale)
    }

    @Test
    fun testReferenceLocaleSurvivesARoundTrip() {
        val original = ModuleConfig(name = "frontend", referenceLocale = "fr")

        val loaded = XmlSerializer.deserialize(XmlSerializer.serialize(original), ModuleConfig::class.java)

        assertEquals("fr", loaded.referenceLocale)
    }

    @Test
    fun testSetConfigKeepsReferenceLocale() {
        val settings = Settings()

        settings.setConfig(Config(modules = listOf(ModuleConfig(name = "frontend", referenceLocale = "en"))))

        assertEquals("en", settings.modules[0].referenceLocale)
    }

    /** Detaches every `<option name="[name]">` element from [element] and its descendants. */
    private fun removeOption(element: Element, name: String) {
        element.children
            .filter { it.name == "option" && it.getAttributeValue("name") == name }
            .forEach { element.removeContent(it) }
        element.children.forEach { removeOption(it, name) }
    }
}
