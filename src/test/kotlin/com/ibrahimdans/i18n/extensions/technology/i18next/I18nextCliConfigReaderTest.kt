package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.plugin.ide.settings.I18nextCliConfig
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.runReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** An i18next-cli configuration file read for the setup wizard, without running it. */
class I18nextCliConfigReaderTest : PlatformBaseTest() {

    private fun read(name: String, text: String): I18nextCliConfig? {
        val file = myFixture.configureByText(name, text)
        return runReadAction { I18nextCliConfigReader.read(file, name) }
    }

    @Test
    fun `the root, separators and default namespace of an i18next config ts are read`() {
        val config = read(
            "i18next.config.ts",
            """
                import { defineConfig } from 'i18next-cli';

                export default defineConfig({
                  locales: ['en', 'fr'],
                  extract: {
                    input: ['src/**/*.{ts,tsx}'],
                    // Where the catalogues are written
                    output: 'public/locales/{{language}}/{{namespace}}.json',
                    defaultNS: "app",
                    keySeparator: '_',
                    nsSeparator: '|',
                  },
                });
            """.trimIndent()
        )

        assertEquals(I18nextCliConfig("i18next.config.ts", "public/locales/{{language}}/{{namespace}}.json", "app", "_", "|"), config)
        assertEquals("public/locales", config?.translationsRoot)
    }

    @Test
    fun `a value computed at runtime or false is left out`() {
        val config = read(
            "i18next.config.ts",
            """
                const SEPARATOR = '.';
                export default {
                  locales: ['en'],
                  extract: {
                    output: (lng: string, ns: string) => `locales/${'$'}{lng}/${'$'}{ns}.json`,
                    defaultNS: false,
                    keySeparator: SEPARATOR,
                    nsSeparator: false,
                  },
                };
            """.trimIndent()
        )

        assertEquals(I18nextCliConfig("i18next.config.ts"), config)
    }

    @Test
    fun `a CommonJS configuration is read the same way`() {
        val config = read("i18next.config.cjs", "module.exports = { locales: ['en'], extract: { output: 'locales/{{language}}.json' } };")

        assertEquals("locales", config?.translationsRoot)
    }

    @Test
    fun `a file without options yields nothing`() {
        assertNull(read("i18next.config.js", "export default {};"))
    }

    private fun readForWizard(path: String, text: String, folders: Set<String> = emptySet()): I18nextCliConfig? {
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        return runReadAction { I18nextCliConfigReader.readForWizard(file, path) { it in folders } }
    }

    @Test
    fun `a next-i18next config gives the root, the default namespace and the fallback language`() {
        val config = readForWizard(
            "next-i18next.config.js",
            """
                module.exports = {
                  i18n: { defaultLocale: 'en', locales: ['en', 'fr'] },
                  localePath: './static/locales',
                  defaultNS: 'common',
                  fallbackLng: 'fr',
                };
            """.trimIndent()
        )

        assertEquals("static/locales", config?.translationsRoot)
        assertEquals("{lang}/{ns}.json", config?.pathTemplate)
        assertEquals("common", config?.defaultNs)
        assertEquals("fr", config?.referenceLocale)
    }

    @Test
    fun `next-i18next defaults to public locales, its default locale and the first namespace`() {
        val config = readForWizard(
            "next-i18next.config.js",
            "module.exports = { i18n: { defaultLocale: 'de', locales: ['de'] }, ns: ['app', 'admin'] };"
        )

        assertEquals("public/locales", config?.translationsRoot)
        assertEquals("app", config?.defaultNs)
        assertEquals("de", config?.referenceLocale)
    }

    @Test
    fun `a computed localePath is not replaced by the default`() {
        val config = readForWizard(
            "next-i18next.config.js",
            "const path = require('path');\nmodule.exports = { i18n: { defaultLocale: 'en' }, localePath: path.resolve('./locales') };"
        )

        assertNull(config?.translationsRoot)
        assertEquals("en", config?.referenceLocale)
    }

    @Test
    fun `a lingui config gives the catalogues' folder and the source locale`() {
        val config = readForWizard(
            "lingui.config.ts",
            """
                import { defineConfig } from '@lingui/cli';

                export default defineConfig({
                  sourceLocale: 'en',
                  locales: ['en', 'fr'],
                  catalogs: [{ path: '<rootDir>/src/locales/{locale}/messages', include: ['src'] }],
                });
            """.trimIndent()
        )

        assertEquals("src/locales", config?.translationsRoot)
        assertEquals("{lang}/messages", config?.pathTemplate)
        assertEquals("en", config?.referenceLocale)
    }

    @Test
    fun `a transloco config gives the root and the default language`() {
        val config = readForWizard(
            "transloco.config.ts",
            """
                import { TranslocoGlobalConfig } from '@jsverse/transloco-utils';

                const config: TranslocoGlobalConfig = {
                  rootTranslationsPath: 'src/assets/i18n',
                  langs: ['en', 'es'],
                  defaultLang: 'es',
                };

                export default config;
            """.trimIndent()
        )

        assertEquals("src/assets/i18n", config?.translationsRoot)
        assertEquals("es", config?.referenceLocale)
    }

    private fun initWith(backend: String) =
        """
            import i18n from 'i18next';
            import Backend from 'i18next-http-backend';

            i18n.use(Backend).init({ fallbackLng: 'en', defaultNS: 'app', backend: { $backend } });
        """.trimIndent()

    @Test
    fun `a loadPath is the catalogues' folder under public, translated to the plugin's template`() {
        val config = readForWizard("src/i18n.ts", initWith("loadPath: '/locales/{{lng}}/{{ns}}.json'"), setOf("public/locales"))

        assertEquals("public/locales", config?.translationsRoot)
        assertEquals("{lang}/{ns}.json", config?.pathTemplate)
        assertEquals("app", config?.defaultNs)
        assertEquals("en", config?.referenceLocale)
    }

    @Test
    fun `a loadPath naming a folder of the project is kept as it is`() {
        val config = readForWizard("src/i18n.ts", initWith("loadPath: 'locales/{{lng}}.json'"), setOf("locales"))

        assertEquals("locales", config?.translationsRoot)
    }

    @Test
    fun `a built loadPath leaves the root to the scan, the defaults still read`() {
        for (built in listOf("loadPath: `${'$'}{BASE}/{{lng}}/{{ns}}.json`", "loadPath: (lng, ns) => '/locales/' + lng")) {
            val config = readForWizard("src/i18n.ts", initWith(built), setOf("public/locales"))

            assertNull(config?.translationsRoot)
            assertEquals("app", config?.defaultNs)
            assertEquals("en", config?.referenceLocale)
        }
    }

    @Test
    fun `a full URL or a folder that does not exist leaves the root to the scan`() {
        assertNull(readForWizard("src/i18n.ts", initWith("loadPath: 'https://cdn.example.com/{{lng}}.json'"))?.translationsRoot)
        assertNull(readForWizard("src/i18n.ts", initWith("loadPath: '/locales/{{lng}}.json'"))?.translationsRoot)
    }

    @Test
    fun `an init with inline resources gives its default namespace and reference locale, not a root`() {
        val init = """
            import i18n from 'i18next';
            import en from './locales/en/app.json';

            i18n.init({ resources: { en: { app: en } }, defaultNS: 'app', fallbackLng: 'fr' });
        """.trimIndent()
        val config = readForWizard("src/i18n.ts", init, setOf("src/locales"))

        assertEquals("app", config?.defaultNs)
        assertEquals("fr", config?.referenceLocale)
        assertNull(config?.translationsRoot)
        assertNull(config?.pathTemplate)
    }

    @Test
    fun `an init stating neither a loadPath nor a default states nothing`() {
        assertNull(readForWizard("src/i18n.ts", "import i18n from 'i18next';\ni18n.init({ resources: {} });"))
        assertNull(readForWizard("src/i18n.ts", "import i18n from 'i18next';\ni18n.init({ lng: 'en' });"))
    }
}
