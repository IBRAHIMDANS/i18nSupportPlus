package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.ide.settings.VueI18nConfig
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.runReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A Nuxt configuration or a vue-i18n `createI18n` call read for the setup wizard, without running it. */
class VueI18nConfigReaderTest : PlatformBaseTest() {

    private fun read(path: String, text: String, folders: Set<String> = emptySet()): VueI18nConfig? {
        val file = myFixture.configureByText(path.substringAfterLast('/'), text)
        return runReadAction { VueI18nConfigReader.read(file, path) { it in folders } }
    }

    @Test
    fun `a typical nuxt config gives the root, the default locale and the format`() {
        val config = read(
            "nuxt.config.ts",
            """
                export default defineNuxtConfig({
                  modules: ['@nuxtjs/i18n'],
                  i18n: {
                    defaultLocale: 'en',
                    langDir: 'lang',
                    locales: [
                      { code: 'en', language: 'en-US', file: 'en.json' },
                      { code: 'fr', file: { path: 'fr.json', cache: false } },
                    ],
                  },
                })
            """.trimIndent()
        )

        assertEquals(VueI18nConfig("nuxt.config.ts", "i18n/lang", "en", listOf("en.json", "fr.json")), config)
    }

    @Test
    fun `langDir is resolved relative to srcDir when the project keeps the v8 layout`() {
        val config = read(
            "nuxt.config.ts",
            """
                export default defineNuxtConfig({
                  srcDir: 'app/',
                  modules: [['@nuxtjs/i18n', { langDir: 'locales', defaultLocale: 'de', locales: [{ code: 'de', files: ['de/common.yaml'] }] }]],
                })
            """.trimIndent(),
            folders = setOf("app/locales"),
        )

        assertEquals(VueI18nConfig("nuxt.config.ts", "app/locales", "de", listOf("de/common.yaml")), config)
    }

    @Test
    fun `the module listed with no options runs on its defaults`() {
        val config = read("nuxt.config.ts", "export default defineNuxtConfig({ modules: ['@nuxtjs/i18n'] })")

        assertEquals(VueI18nConfig("nuxt.config.ts", "i18n/locales"), config)
    }

    @Test
    fun `options built at runtime state nothing`() {
        val built = read(
            "nuxt.config.ts",
            """
                import { i18nOptions } from './config/i18n'
                export default defineNuxtConfig({ modules: ['@nuxtjs/i18n'], i18n: i18nOptions() })
            """.trimIndent()
        )
        val computedDir = read(
            "nuxt.config.ts",
            """
                import { resolve } from 'node:path'
                export default defineNuxtConfig({ i18n: { langDir: resolve(__dirname, 'lang'), defaultLocale: 'en' } })
            """.trimIndent()
        )

        assertEquals(VueI18nConfig("nuxt.config.ts"), built)
        assertEquals(VueI18nConfig("nuxt.config.ts", referenceLocale = "en"), computedDir)
    }

    @Test
    fun `a nuxt config without the i18n module states nothing`() {
        assertNull(read("nuxt.config.ts", "export default defineNuxtConfig({ modules: ['@pinia/nuxt'] })"))
    }

    @Test
    fun `createI18n gives the fallback locale and the folder of the imported catalogues`() {
        val config = read(
            "src/i18n.ts",
            """
                import { createI18n } from 'vue-i18n'
                import en from './locales/en.json'
                import fr from './locales/fr.json'

                export const i18n = createI18n({
                  legacy: false,
                  locale: 'fr',
                  fallbackLocale: 'en',
                  messages: { en, fr: fr },
                })
            """.trimIndent()
        )

        assertEquals(VueI18nConfig("src/i18n.ts", "src/locales", "en", listOf("en.json", "fr.json")), config)
    }

    @Test
    fun `createI18n with messages built at runtime gives only its locale`() {
        val config = read(
            "src/main.ts",
            """
                import { createI18n } from 'vue-i18n'
                import { loadMessages } from './messages'

                createI18n({ locale: 'ja', messages: loadMessages() })
            """.trimIndent()
        )

        assertEquals(VueI18nConfig("src/main.ts", referenceLocale = "ja"), config)
    }

    @Test
    fun `a file without createI18n states nothing`() {
        assertNull(read("src/main.ts", "import { createApp } from 'vue'\ncreateApp({}).mount('#app')"))
    }
}
