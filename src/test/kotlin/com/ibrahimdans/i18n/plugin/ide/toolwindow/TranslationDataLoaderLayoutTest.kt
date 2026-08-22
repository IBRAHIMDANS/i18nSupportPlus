package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the tool window, the stats, the CSV export and *Sync Keys* all read.
 *
 * On a "one file per locale" project (`locales/en.json` + `locales/fr.json`), the file stem
 * was taken as the namespace, so `en` and `fr` became two namespaces and every key was
 * indexed twice — `en:menu.home` holding the English value, `fr:menu.home` the French one,
 * neither present in the other locale. Keys appeared in double in the tree and the table,
 * about half the translations were counted as missing, the CSV export carried the doubling,
 * and *Sync Keys* offered to create the `en:*` keys inside `fr.json`.
 *
 * The companion defect — a non-language folder read as a locale (`src/api/common.json` under
 * the locale `api`) — only shows up with a configured translations root, which no fixture can
 * set: the fixture's temp VFS is not the project's basePath, so the root never matches. It is
 * covered where the rule itself lives, in `LocaleNamingTest` and in `TranslationDataLoaderTest`.
 */
class TranslationDataLoaderLayoutTest : PlatformBaseTest() {

    private fun load() = TranslationDataLoader.loadAllTranslations(project)

    // ---- one file per locale ----

    @Test
    fun oneFilePerLocaleIndexesEachKeyOnce() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("locales/fr.json", """{"menu": {"home": "Accueil"}}""")

        val translations = load()

        assertEquals(setOf("menu.home"), translations.keys, "the key must not be prefixed by its locale")
        assertEquals(mapOf("en" to "Home", "fr" to "Accueil"), translations["menu.home"])
    }

    @Test
    fun oneFilePerLocaleStillReportsAMissingTranslation() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en.json", """{"menu": {"home": "Home", "about": "About"}}""")
        addFileToProject("locales/fr.json", """{"menu": {"home": "Accueil"}}""")

        val translations = load()

        assertEquals(setOf("menu.home", "menu.about"), translations.keys)
        assertEquals(mapOf("en" to "Home", "fr" to "Accueil"), translations["menu.home"])
        assertEquals(mapOf("en" to "About"), translations["menu.about"], "fr is genuinely missing here")
    }

    // ---- layouts that must not change ----

    @Test
    fun multiNamespaceLayoutKeepsItsPrefixes() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}}""")
        addFileToProject("locales/en/auth.json", """{"login": "Log in"}""")
        addFileToProject("locales/fr/common.json", """{"user": {"name": "Nom"}}""")

        val translations = load()

        assertEquals(setOf("common:user.name", "auth:login"), translations.keys)
        assertEquals(mapOf("en" to "Name", "fr" to "Nom"), translations["common:user.name"])
    }

    @Test
    fun theDefaultNamespaceIsNeverPrefixed() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/translation.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("locales/fr/translation.json", """{"menu": {"home": "Accueil"}}""")

        val translations = load()

        assertEquals(setOf("menu.home"), translations.keys)
        assertEquals(mapOf("en" to "Home", "fr" to "Accueil"), translations["menu.home"])
    }
}
