package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The settings diagnostics, checked without a running IDE.
 *
 * The rules used to live inside the banner's `JPanel`, where the only way to reach them was to
 * build the panel — so they were never tested at all, and a check that stopped firing would
 * have shipped silently: a banner that says nothing looks exactly like a healthy config.
 * [ConfigDiagnostics] takes a [DirectoryProbe] precisely so the file system can be stubbed here.
 */
class ConfigDiagnosticsTest {

    /** A probe answering from a fixed table; anything unlisted is reported as missing. */
    private fun probe(vararg states: Pair<String, DirectoryState>): DirectoryProbe {
        val table = states.toMap()
        return DirectoryProbe { path -> table[path] ?: DirectoryState.MISSING }
    }

    private val healthy = DirectoryProbe { DirectoryState.OK }

    @Test
    fun `a configured directory that does not exist is reported with its path`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(translationsRoot = "src/locales"),
            probe("src/locales" to DirectoryState.MISSING)
        )

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.ROOT_MISSING, module = null, path = "src/locales")),
            diagnostics
        )
    }

    @Test
    fun `an unconfigured root is reported so the wizard can be offered`() {
        val diagnostics = ConfigDiagnostics.inspect(Config(translationsRoot = ""), healthy)

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.ROOT_NOT_CONFIGURED)),
            diagnostics
        )
    }

    @Test
    fun `a directory holding no translation file is reported`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(translationsRoot = "locales"),
            probe("locales" to DirectoryState.WITHOUT_TRANSLATIONS)
        )

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.ROOT_WITHOUT_TRANSLATIONS, module = null, path = "locales")),
            diagnostics
        )
    }

    @Test
    fun `a healthy config produces no diagnostic at all`() {
        val diagnostics = ConfigDiagnostics.inspect(Config(translationsRoot = "locales"), healthy)

        assertTrue(diagnostics.isEmpty(), "no banner should be shown for a sound configuration")
    }

    @Test
    fun `a broken module root names the module, not only the path`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(
                translationsRoot = "locales",
                modules = listOf(ModuleConfig(name = "frontend", rootDirectory = "apps/web/locales"))
            ),
            probe("locales" to DirectoryState.OK, "apps/web/locales" to DirectoryState.MISSING)
        )

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.ROOT_MISSING, module = "frontend", path = "apps/web/locales")),
            diagnostics
        )
    }

    @Test
    fun `an unnamed module falls back to its root directory`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(
                translationsRoot = "locales",
                modules = listOf(ModuleConfig(name = "", rootDirectory = "apps/web/locales"))
            ),
            probe("locales" to DirectoryState.OK, "apps/web/locales" to DirectoryState.WITHOUT_TRANSLATIONS)
        )

        assertEquals(
            listOf(
                ConfigDiagnostic(
                    ConfigIssue.ROOT_WITHOUT_TRANSLATIONS,
                    module = "apps/web/locales",
                    path = "apps/web/locales"
                )
            ),
            diagnostics
        )
    }

    @Test
    fun `modules carrying their own roots make the project-wide one optional`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(
                translationsRoot = "",
                modules = listOf(ModuleConfig(name = "frontend", rootDirectory = "apps/web/locales"))
            ),
            healthy
        )

        assertTrue(diagnostics.isEmpty(), "the wizard must not be offered when a module already points at the files")
    }

    @Test
    fun `an empty default namespace and key separator are still reported`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(translationsRoot = "locales", defaultNs = "", keySeparator = ""),
            healthy
        )

        assertEquals(
            listOf(
                ConfigDiagnostic(ConfigIssue.DEFAULT_NS_EMPTY),
                ConfigDiagnostic(ConfigIssue.KEY_SEPARATOR_EMPTY)
            ),
            diagnostics
        )
    }

    private val cboxFiles = mapOf("en" to setOf("common", "auth"), "fr" to setOf("common", "auth"))
    private val initCode = CodeDefaults("src/i18n.ts", defaultNs = listOf("common"))

    @Test
    fun `a default namespace with no file is reported with the namespace the code names`() {
        val diagnostics = ConfigDiagnostics.inspect(
            Config(translationsRoot = "public/locales"),
            healthy,
            NamespaceFacts(byLocale = cboxFiles, code = initCode)
        )

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_NO_FILE, path = "translation", suggestion = "common", origin = "src/i18n.ts")),
            diagnostics
        )
    }

    @Test
    fun `without the code the only namespace of every locale is suggested`() {
        val files = mapOf("en" to setOf("app", "extra"), "fr" to setOf("app"))

        val diagnostic = ConfigDiagnostics.defaultNamespaceWithoutFile(Config(), NamespaceFacts(byLocale = files))

        assertEquals(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_NO_FILE, path = "translation", suggestion = "app"), diagnostic)
    }

    @Test
    fun `several namespaces in every locale and no code leave only the wizard`() {
        val diagnostic = ConfigDiagnostics.defaultNamespaceWithoutFile(Config(), NamespaceFacts(byLocale = cboxFiles))

        assertEquals(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_NO_FILE, path = "translation"), diagnostic)
    }

    @Test
    fun `a default namespace with a file, a locale-named file or unknown files report nothing`() {
        assertEquals(null, ConfigDiagnostics.defaultNamespaceWithoutFile(Config(defaultNs = "common"), NamespaceFacts(byLocale = cboxFiles)))
        assertEquals(null, ConfigDiagnostics.defaultNamespaceWithoutFile(Config(), NamespaceFacts(byLocale = cboxFiles, holdsDefaultByName = true)))
        assertEquals(null, ConfigDiagnostics.defaultNamespaceWithoutFile(Config(), NamespaceFacts()))
    }

    @Test
    fun `a blank default namespace read from the code says where it comes from instead of being empty`() {
        val config = Config(translationsRoot = "locales", defaultNs = "").withCodeDefaults(initCode)

        val diagnostics = ConfigDiagnostics.inspect(config, healthy, NamespaceFacts(byLocale = cboxFiles, code = initCode, readFromCode = true))

        assertEquals(listOf(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_FROM_CODE, path = "common", origin = "src/i18n.ts")), diagnostics)
    }
}
