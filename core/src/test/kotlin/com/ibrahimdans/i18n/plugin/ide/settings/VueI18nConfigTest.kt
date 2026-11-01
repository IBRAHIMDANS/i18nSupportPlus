package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Where a Nuxt `langDir`, or the catalogues a `createI18n` call imports, point in the project. */
class VueI18nConfigTest {

    private fun nuxtRoot(
        langDir: String? = null,
        restructureDir: String? = null,
        srcDir: String? = null,
        folders: Set<String> = emptySet(),
    ): String? = VueI18nConfig.nuxtTranslationsRoot(langDir, restructureDir, srcDir) { it in folders }

    @Test
    fun `langDir is resolved from the i18n folder, as nuxt i18n v9 and later do`() {
        Assertions.assertEquals("i18n/lang", nuxtRoot(langDir = "lang"))
        Assertions.assertEquals("i18n/locales", nuxtRoot(), "langDir defaults to locales")
    }

    @Test
    fun `a v8 project keeps its langDir under srcDir when only that folder exists`() {
        Assertions.assertEquals("app/lang", nuxtRoot(langDir = "lang", srcDir = "app/", folders = setOf("app/lang")))
        Assertions.assertEquals("lang", nuxtRoot(langDir = "./lang", folders = setOf("lang")))
    }

    @Test
    fun `the v9 folder wins when both exist`() {
        Assertions.assertEquals("i18n/lang", nuxtRoot(langDir = "lang", folders = setOf("lang", "i18n/lang")))
    }

    @Test
    fun `restructureDir moves the base, and false puts it back on srcDir`() {
        Assertions.assertEquals("translations/locales", nuxtRoot(restructureDir = "translations"))
        Assertions.assertEquals("src/locales", nuxtRoot(restructureDir = "", srcDir = "src"))
    }

    @Test
    fun `a langDir climbing out of the i18n folder is normalized, out of the project or absolute it is dropped`() {
        Assertions.assertEquals("locales", nuxtRoot(langDir = "../locales"))
        Assertions.assertNull(nuxtRoot(langDir = "../../locales"))
        Assertions.assertNull(nuxtRoot(langDir = "/var/locales"))
    }

    @Test
    fun `the imported catalogues give their common folder, resolved from the importing file`() {
        Assertions.assertEquals("src/locales", VueI18nConfig.commonFolder("src", listOf("./locales/en.json", "./locales/fr.json")))
        Assertions.assertEquals(
            "locales",
            VueI18nConfig.commonFolder("src/i18n", listOf("../../locales/en/app.json", "../../locales/fr/app.json")),
        )
    }

    @Test
    fun `a catalogue imported from a package, or no catalogue, gives no folder`() {
        Assertions.assertNull(VueI18nConfig.commonFolder("src", listOf("./locales/en.json", "@acme/messages/fr.json")))
        Assertions.assertNull(VueI18nConfig.commonFolder("src", emptyList()))
    }
}
