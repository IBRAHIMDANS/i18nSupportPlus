package com.ibrahimdans.i18n.plugin.ide.settings

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
}
