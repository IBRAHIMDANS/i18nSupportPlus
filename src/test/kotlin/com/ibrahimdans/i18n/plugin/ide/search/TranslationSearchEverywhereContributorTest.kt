package com.ibrahimdans.i18n.plugin.ide.search

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.openapi.progress.EmptyProgressIndicator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The matching rule alone, on the map shape [com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader] returns. */
class TranslationSearchHitsTest {

    private val translations = mapOf(
        "common:greeting.welcome" to mapOf("en" to "Welcome back", "fr" to "Bon retour"),
        "common:menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
        "auth:login" to mapOf("en" to "Log in", "fr" to "Connexion"),
    )

    @Test
    fun `matches on the value`() {
        assertEquals(
            listOf(TranslationSearchHit("common:greeting.welcome", "en", "Welcome back")),
            findTranslationHits(translations, "Welcome back"),
        )
    }

    @Test
    fun `matches on the key, in every locale holding it`() {
        assertEquals(
            listOf(
                TranslationSearchHit("common:menu.home", "en", "Home"),
                TranslationSearchHit("common:menu.home", "fr", "Accueil"),
            ),
            findTranslationHits(translations, "menu.home"),
        )
    }

    @Test
    fun `searches every locale`() {
        assertEquals(
            listOf(TranslationSearchHit("auth:login", "fr", "Connexion")),
            findTranslationHits(translations, "connexion"),
        )
    }

    @Test
    fun `ignores case on values and keys`() {
        assertEquals(listOf("en"), findTranslationHits(translations, "WELCOME BACK").map { it.locale })
        assertEquals(2, findTranslationHits(translations, "AUTH:LOGIN").size)
    }

    @Test
    fun `lists value matches before key-only matches`() {
        // "home" is the value of menu.home in en, and only its key in fr.
        val hits = findTranslationHits(translations, "home")
        assertEquals(listOf("en", "fr"), hits.map { it.locale })
        assertEquals("Home", hits.first().value)
    }

    @Test
    fun `finds nothing for a blank pattern or on an empty project`() {
        assertTrue(findTranslationHits(translations, "   ").isEmpty())
        assertTrue(findTranslationHits(emptyMap(), "welcome").isEmpty())
    }
}

/** The contributor as Search Everywhere drives it: registered, fed by the loader, navigating to the key. */
class TranslationSearchEverywhereContributorTest : PlatformBaseTest() {

    private fun contributor() = TranslationSearchEverywhereContributor(project, null)

    private fun search(pattern: String): List<TranslationSearchHit> {
        val hits = mutableListOf<TranslationSearchHit>()
        contributor().fetchElements(pattern, EmptyProgressIndicator()) { hits += it; true }
        return hits
    }

    @Test
    fun `is registered as a Search Everywhere contributor`() {
        assertTrue(
            SearchEverywhereContributor.EP_NAME.extensionList.any { it is TranslationSearchEverywhereContributor.Factory },
            "the factory must be declared in plugin.xml",
        )
    }

    @Test
    fun `finds a key from its text, in every locale`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"greeting": {"welcome": "Welcome back"}}""")
        addFileToProject("locales/fr/common.json", """{"greeting": {"welcome": "Bon retour"}}""")

        assertEquals(
            listOf(TranslationSearchHit("common:greeting.welcome", "en", "Welcome back")),
            search("welcome BACK"),
        )
        assertEquals(listOf("fr"), search("bon retour").map { it.locale })
        assertEquals(listOf("en", "fr"), search("greeting.welcome").map { it.locale })
    }

    @Test
    fun `finds nothing on a project without translations`() = myFixture.runWithConfig(Config()) {
        assertTrue(search("welcome").isEmpty())
    }

    @Test
    fun `opens the translation file at the key`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", "{\n  \"greeting\": {\n    \"welcome\": \"Welcome back\"\n  }\n}")
        val contributor = contributor()
        val hit = TranslationSearchHit("common:greeting.welcome", "en", "Welcome back")
        contributor.fetchElements("welcome", EmptyProgressIndicator()) { true }

        val target = requireNotNull(contributor.locate(hit)) { "the key must resolve to its file" }

        assertEquals("common.json", target.first.name)
        val text = String(target.first.contentsToByteArray())
        // The leaf resolves to its value literal, exactly as F4 in the table: the caret lands on the
        // key's line, not on the enclosing object.
        val line = text.substring(0, target.second).substringAfterLast('\n') + text.substring(target.second).substringBefore('\n')
        assertTrue(line.contains("\"welcome\""), "the offset must land on the key's line, was ${target.second}: '$line'")
    }
}
