package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class ConfigTest {

    @Test
    fun `defaults read as an untouched project`() {
        val config = Config()

        assertTrue(config.searchInProjectOnly)
        assertEquals(":", config.nsSeparator)
        assertEquals(".", config.keySeparator)
        assertEquals(listOf("translation"), config.defaultNamespaces())
        assertEquals(emptyList<String>(), config.fallbackNamespaces())
        assertFalse(config.usesFlatKeys())
        assertEquals(emptySet<String>(), config.excludedDirectorySet())
        assertEquals(emptySet<String>(), config.excludedFileExtensionSet())
    }

    @Test
    fun `default namespaces split on any separator and fall back when blank`() {
        assertEquals(listOf("common", "admin", "errors"), Config(defaultNs = "common; admin|errors").defaultNamespaces())
        assertEquals(listOf("translation"), Config(defaultNs = "  ").defaultNamespaces())
    }

    @Test
    fun `gettext and flatKeys both read keys as flat`() {
        assertTrue(Config(gettext = true).usesFlatKeys())
        assertTrue(Config(flatKeys = true).usesFlatKeys())
    }

    @Test
    fun `exclusion sets are trimmed and extensions lose their dot and case`() {
        val config = Config(excludedDirectories = " node_modules, dist ,,", excludedFileExtensions = ".JSON, yml ,")

        assertEquals(setOf("node_modules", "dist"), config.excludedDirectorySet())
        assertEquals(setOf("json", "yml"), config.excludedFileExtensionSet())
    }

    @Test
    fun `exclusion lists split on any separator like the other lists`() {
        assertEquals(setOf("a", "b", "c", "d"), Config(excludedDirectories = "a; b,c d").excludedDirectorySet())
        assertEquals(setOf("json", "yml", "po"), Config(excludedFileExtensions = ".json; yml|.PO").excludedFileExtensionSet())
    }

    @Test
    fun `a root resolves under the project unless it is an absolute path inside it`() {
        assertEquals("/p/src/locales", Config.resolveRoot("./src/locales/", "/p"))
        assertEquals("/p/src/locales", Config.resolveRoot("/src/locales", "/p"), "a leading slash is the project root")
        assertEquals("/p/src/locales", Config.resolveRoot("/p/src/locales", "/p/"), "an absolute path inside the project")
        assertEquals("/p", Config.resolveRoot("/p", "/p"))
        assertEquals("/p", Config.resolveRoot("", "/p"), "no root is the project itself")
        assertEquals("/p/elsewhere/locales", Config.resolveRoot("/elsewhere/locales", "/p"), "an absolute path outside the project is read under it")
    }

    @Test
    fun `the translations root reads the same whatever its spelling`() {
        for (root in listOf("src/locales", "./src/locales", "src\\locales", " src/locales/ ")) {
            assertEquals("src/locales", Config(translationsRoot = root).normalizedTranslationsRoot(), "root spelled '$root'")
        }
        assertEquals("/src/locales", Config(translationsRoot = " /src/locales/ ").normalizedTranslationsRoot(), "a leading slash is kept")
        assertEquals("C:/app/locales", Config(translationsRoot = "C:\\app\\locales\\").normalizedTranslationsRoot())
        assertEquals("", Config(translationsRoot = " ./ ").normalizedTranslationsRoot())
        assertEquals("", Config(translationsRoot = "/").normalizedTranslationsRoot())
    }

    @Test
    fun `an ignored locale covers its regional variants`() {
        val config = Config(ignoredLocales = "de, en-GB")

        assertTrue(config.ignoresLocale("de-DE"))
        assertTrue(config.ignoresLocale("en-GB"))
        assertFalse(config.ignoresLocale("en-US"))
    }

    @Test
    fun `a localization setting is read by id and name`() {
        val config = Config(localizationConfig = mapOf("json/indent" to "4"))

        assertEquals("4", config.getLocalizationSetting("json", "indent"))
        assertNull(config.getLocalizationSetting("yaml", "indent"))
    }

    @Test
    fun `locale aliases are read as alias to locale, blanks and halves left out`() {
        val config = Config(localeAliases = " gb = en-GB, cn=zh-CN;jp=ja , broken, =fr, x= ")

        assertEquals(mapOf("gb" to "en-GB", "cn" to "zh-CN", "jp" to "ja"), config.localeAliasMap())
        assertEquals("en-GB", config.aliasedLocale("gb"))
        assertEquals("fr", config.aliasedLocale("fr"))
        assertEquals(emptyMap<String, String>(), Config().localeAliasMap())
    }

    @Test
    fun `the parent locale is named as the project writes it`() {
        assertEquals("en", Config().parentLocale("en-GB"))
        assertEquals("uk", Config(localeAliases = "uk=en").parentLocale("en-GB"))
        assertEquals("uk", Config(localeAliases = "uk=en, gb=en-GB").parentLocale("gb"))
        assertNull(Config().parentLocale("en"))
        assertNull(Config(localeAliases = "uk=en", regionalFallback = false).parentLocale("en-GB"))
    }

    private val code = CodeDefaults("src/i18n.ts", defaultNs = listOf("common", "shared"), referenceLocale = "fr")

    @Test
    fun `blank settings take what the code says, filled ones keep their value`() {
        val filled = Config(defaultNs = "", foldingPreferredLanguage = "").withCodeDefaults(code)
        assertEquals(listOf("common", "shared"), filled.defaultNamespaces())
        assertEquals("fr", filled.foldingPreferredLanguage)

        val kept = Config(defaultNs = "app").withCodeDefaults(code)
        assertEquals(listOf("app"), kept.defaultNamespaces())
        assertEquals("en", kept.foldingPreferredLanguage)
        assertFalse(Config().leavesBlankForCode())
        assertTrue(Config(defaultNs = "").leavesBlankForCode())
    }

    @Test
    fun `saving keeps a blank setting blank while it still holds the value read from the code`() {
        val stored = Config(defaultNs = "")
        val shown = stored.withCodeDefaults(code)

        assertEquals("", shown.keepingCodeDefaultsOut(stored, code).defaultNs)
        assertEquals("auth", shown.copy(defaultNs = "auth").keepingCodeDefaultsOut(stored, code).defaultNs)
        assertEquals("common,shared", Config(defaultNs = "common,shared").keepingCodeDefaultsOut(Config(defaultNs = "common,shared"), code).defaultNs)
    }

    @Test
    fun `every setting has a scope`() {
        // Java reflection, core has no kotlin-reflect: a property is a field with a public getter.
        val properties = Config::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filter { name ->
                Config::class.java.methods.any { it.name == "get" + name.replaceFirstChar(Char::uppercase) && it.parameterCount == 0 }
            }
            .toSet()

        assertEquals(emptySet<String>(), properties - Config.SCOPES.keys, "settings of Config without their line in Config.SCOPES")
        assertEquals(emptySet<String>(), Config.SCOPES.keys - properties, "lines of Config.SCOPES that name no setting of Config")
    }
}
