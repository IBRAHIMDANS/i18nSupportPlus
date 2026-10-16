package com.ibrahimdans.i18n.extensions.technology.paraglide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `project.inlang/settings.json`, read without the platform: where each locale's messages live. */
class InlangProjectSettingsTest {

    private fun settings(pathPattern: String, extra: String = "") = """
        { "baseLocale": "en", "locales": ["en", "fr"]$extra,
          "plugin.inlang.messageFormat": { "pathPattern": $pathPattern } }
    """.trimIndent()

    @Test
    fun aStringPatternGivesOneFilePerLocale() {
        val parsed = InlangProjectSettings.parse(settings("\"./messages/{locale}.json\""))!!
        assertEquals("en", parsed.baseLocale)
        assertEquals(listOf("en" to "messages/en.json", "fr" to "messages/fr.json"), parsed.messageFiles())
    }

    @Test
    fun anArrayOfPatternsGivesEachOfThem() {
        val parsed = InlangProjectSettings.parse(settings("[\"./messages/{locale}.json\", \"./extra/{locale}.json\"]"))!!
        assertEquals(
            listOf("en" to "messages/en.json", "en" to "extra/en.json", "fr" to "messages/fr.json", "fr" to "extra/fr.json"),
            parsed.messageFiles()
        )
    }

    /** inlang's first format: `languageTags`, `sourceLanguageTag`, `{languageTag}`, no `./`. */
    @Test
    fun theOlderNamesAreRead() {
        val text = """
            { "sourceLanguageTag": "de", "languageTags": ["de"],
              "plugin.inlang.messageFormat": { "pathPattern": "translations/{languageTag}.json" } }
        """.trimIndent()
        val parsed = InlangProjectSettings.parse(text)!!
        assertEquals("de", parsed.baseLocale)
        assertEquals(listOf("de" to "translations/de.json"), parsed.messageFiles())
    }

    /** Another plugin (i18next's) writes its own format: no message-format pattern, no file. */
    @Test
    fun withoutTheMessageFormatPluginThereIsNoFile() {
        val text = """{ "baseLocale": "en", "locales": ["en"], "plugin.inlang.i18next": { "pathPattern": "./{locale}.json" } }"""
        assertEquals(emptyList<Pair<String, String>>(), InlangProjectSettings.parse(text)!!.messageFiles())
    }

    @Test
    fun invalidOrEmptySettingsReadAsNothing() {
        assertNull(InlangProjectSettings.parse("{ not json"))
        assertNull(InlangProjectSettings.parse("[]"))
        assertNull(InlangProjectSettings.parse("""{ "baseLocale": "en" }"""))
    }
}
