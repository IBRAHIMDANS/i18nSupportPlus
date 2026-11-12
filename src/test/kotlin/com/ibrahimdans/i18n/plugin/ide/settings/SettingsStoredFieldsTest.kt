package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Every setting of [Config] goes through [Settings] and its XML and comes back as it was: a setting
 * left out of the stored fields, of `storedConfig()` or of the XML is reset on the next IDE start.
 */
class SettingsStoredFieldsTest : PlatformBaseTest() {

    /** Each setting away from its default, so that one left out comes back at its default and differs. */
    private val edited = Config(
        searchInProjectOnly = false,
        nsSeparator = "::",
        keySeparator = "/",
        pluralSeparator = "_",
        defaultNs = "common",
        fallbackNs = "shared",
        firstComponentNs = true,
        jsConfiguration = "src/i18n.ts",
        foldingEnabled = true,
        foldingPreferredLanguage = "fr",
        foldingMaxLength = 42,
        extractSorted = true,
        gettext = true,
        gettextAliases = "t",
        flatKeys = true,
        partialTranslationInspectionEnabled = true,
        preferredLocalization = "yaml",
        localizationConfig = mapOf("json.indent" to "4"),
        sortKeysAlphabetically = true,
        keyOrder = KeyOrder.NATURAL,
        previewLocale = "de",
        translationsRoot = "src/locales",
        excludedDirectories = "dist",
        excludedFileExtensions = "bak",
        keptKeys = "errors.offline",
        ignoredLocales = "pt",
        regionalFallback = false,
        localeAliases = "gb=en-GB",
        gutterIconsEnabled = false,
        setupWizardEnabled = false,
        modules = listOf(ModuleConfig(name = "front", rootDirectory = "web/i18n", enabled = false)),
        rules = listOf(EditorRuleState(id = "r1", language = "js", priority = 3)),
    )

    @Test
    fun `the edited settings are away from every default`() {
        val defaults = Config()
        val same = Config.SCOPES.keys.filter { name ->
            val getter = Config::class.java.getMethod("get" + name.replaceFirstChar(Char::uppercase))
            getter.invoke(edited) == getter.invoke(defaults)
        }
        Assertions.assertEquals(emptyList<String>(), same, "settings this test would not see left out")
    }

    @Test
    fun `every setting stored comes back as it was`() {
        val settings = Settings().apply { setConfig(edited) }

        Assertions.assertEquals(edited, settings.config())
    }

    @Test
    fun `every setting comes back from the XML`() {
        // A project's first state, as the platform starts it: no migration runs on it again.
        val saved = XmlSerializer.serialize(Settings().apply { noStateLoaded(); setConfig(edited) })

        val loaded = Settings().apply { loadState(XmlSerializer.deserialize(saved, Settings::class.java)) }

        Assertions.assertEquals(edited, loaded.config())
    }

    @Test
    fun `a format never chosen is read as the first registered, and not stored`() {
        val settings = Settings()

        Assertions.assertTrue(settings.config().preferredLocalization.isNotEmpty())
        Assertions.assertEquals("", settings.preferredLocalization, "reading the settings writes nothing")
    }

    @Test
    fun `the stored modules are copies`() {
        val module = ModuleConfig(name = "front")
        val settings = Settings().apply { setConfig(Config(modules = listOf(module))) }

        module.name = "changed"

        Assertions.assertEquals("front", settings.config().modules.single().name)
    }
}
