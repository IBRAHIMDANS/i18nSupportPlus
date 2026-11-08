package com.ibrahimdans.i18n.extensions.technology.angulari18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `angular.json`'s `i18n` and the metadata of Angular's markers, read without the platform. */
class AngularI18nConfigTest {

    private fun workspace(i18n: String, architect: String = "") = """
        { "version": 1, "projects": { "shop": { "root": "", $architect "i18n": $i18n } } }
    """.trimIndent()

    // ── angular.json ──────────────────────────────────────────────────────────

    @Test
    fun eachLocaleIsAPathAnArrayOrATranslationObject() {
        val project = AngularI18nConfig.parse(workspace("""
            { "sourceLocale": "en-GB", "locales": {
                "fr": "src/locale/messages.fr.xlf",
                "de": { "translation": "src/locale/messages.de.xlf", "baseHref": "/de/" },
                "es": ["src/locale/messages.es.xlf", "./src/locale/extra.es.xlf"] } }
        """)).single()

        assertEquals("shop", project.name)
        assertEquals("en-GB", project.sourceLocale)
        assertEquals(
            listOf(
                "fr" to "src/locale/messages.fr.xlf",
                "de" to "src/locale/messages.de.xlf",
                "es" to "src/locale/messages.es.xlf",
                "es" to "src/locale/extra.es.xlf",
            ),
            project.translations,
        )
    }

    @Test
    fun theSourceLocaleIsAStringAnObjectOrAngularsDefault() {
        assertEquals("fr", AngularI18nConfig.parse(workspace("""{ "sourceLocale": { "code": "fr", "baseHref": "" } }""")).single().sourceLocale)
        assertEquals("en-US", AngularI18nConfig.parse(workspace("""{ "locales": {} }""")).single().sourceLocale)
    }

    /** `ng extract-i18n` writes where its options say, else the place the translations were copied from. */
    @Test
    fun theReferenceFileIsTheExtractionOutputThenBesideTheTranslationsThenAtTheRoot() {
        val withOutput = AngularI18nConfig.parse(workspace(
            """{ "locales": { "fr": "src/locale/messages.fr.xlf" } }""",
            """ "architect": { "extract-i18n": { "options": { "outputPath": "i18n/", "outFile": "source.xlf" } } },""",
        )).single()
        assertEquals(listOf("i18n/source.xlf", "src/locale/source.xlf", "source.xlf"), withOutput.referenceFiles)

        val bare = AngularI18nConfig.parse(workspace("""{ "locales": { "fr": "src/locale/messages.fr.xlf" } }""")).single()
        assertEquals(listOf("src/locale/messages.xlf", "messages.xlf"), bare.referenceFiles)
    }

    @Test
    fun aProjectWithoutI18nOrAnUnreadableFileGivesNothing() {
        assertTrue(AngularI18nConfig.parse("""{ "projects": { "shop": { "root": "" } } }""").isEmpty())
        assertTrue(AngularI18nConfig.parse("not json").isEmpty())
        assertTrue(AngularI18nConfig.parse("[]").isEmpty())
    }

    // ── Markers ───────────────────────────────────────────────────────────────

    @Test
    fun theIdIsWhatFollowsTheMarkerOfTheMetadata() {
        assertEquals("introductionHeader", AngularI18nIds.idOf("site header|An introduction header@@introductionHeader"))
        assertEquals("home.title", AngularI18nIds.idOf("@@home.title"))
        assertEquals("home.title", AngularI18nIds.idOf(" @@home.title "))
    }

    /** Without a custom id, `ng extract-i18n` computes one from the text: the code names no key. */
    @Test
    fun aMarkerWithoutCustomIdNamesNoKey() {
        assertNull(AngularI18nIds.idOf("site header|An introduction header"))
        assertNull(AngularI18nIds.idOf(""))
        assertNull(AngularI18nIds.idOf("@@"))
        assertNull(AngularI18nIds.idOf("@@two words"))
    }

    @Test
    fun theLocalizeIdIsInTheMetadataBlock() {
        assertEquals("home.title", AngularI18nIds.localizeIdOf("`:@@home.title:Welcome`"))
        assertEquals("greeting", AngularI18nIds.localizeIdOf("`:meaning|description@@greeting:Hello \${name}!`"))
        assertNull(AngularI18nIds.localizeIdOf("`Welcome`"))
        assertNull(AngularI18nIds.localizeIdOf("`:meaning|description:Welcome`"))
        assertNull(AngularI18nIds.localizeIdOf("`:@@unterminated`"))
    }

    @Test
    fun theI18nAttributesAreTheMarkerAndItsAttributeForms() {
        assertTrue(AngularI18nIds.isI18nAttribute("i18n"))
        assertTrue(AngularI18nIds.isI18nAttribute("i18n-title"))
        assertTrue(!AngularI18nIds.isI18nAttribute("i18nTitle"))
        assertTrue(!AngularI18nIds.isI18nAttribute("title"))
    }
}
