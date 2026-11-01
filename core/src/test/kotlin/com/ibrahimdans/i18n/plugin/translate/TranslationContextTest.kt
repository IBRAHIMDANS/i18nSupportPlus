package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationContextTest {

    private val config = Config()

    private val wizard = mapOf(
        "wizard:review.generation.caption" to mapOf("en" to "Which media would you like to create now?"),
        "wizard:review.generation.images" to mapOf("en" to "Generate images", "de" to "Bilder generieren"),
        "wizard:review.generation.narration" to mapOf("en" to "Generate narration"),
        "wizard:story.title" to mapOf("en" to "Generate a story", "de" to "Eine Geschichte generieren"),
        "wizard:settings.cancel" to mapOf("en" to "Cancel", "de" to "Abbrechen"),
    )

    private fun contextOf(
        key: String, known: Map<String, Map<String, String>>, text: String = known[key]?.get("en").orEmpty(), cfg: Config = config,
    ) = TranslationContext.of(TranslationContext.header(listOf(key)), listOf(key), listOf(text), known, "en", "de", cfg)

    @Test
    fun `the sibling already translated comes first, then a pair sharing a word`() {
        assertEquals(
            "A UI string of an application, under the i18n key wizard:review.generation.narration. " +
                "Already translated under the same parent key: images: \"Generate images\" → \"Bilder generieren\". " +
                "Other translations of this project sharing its words: \"Generate a story\" → \"Eine Geschichte generieren\"",
            contextOf("wizard:review.generation.narration", wizard)
        )
    }

    @Test
    fun `nothing to add leaves the key sentence alone`() {
        val header = TranslationContext.header(listOf("wizard:settings.save"))
        assertEquals(header, contextOf("wizard:settings.save", emptyMap(), "Save"))
        assertEquals(header, contextOf("wizard:settings.save", wizard - "wizard:settings.cancel", "Save"), "no sibling, no shared word")
    }

    @Test
    fun `an unrelated pair is left out, and a sibling untranslated in the target is no example`() {
        val context = contextOf("wizard:review.generation.narration", wizard)
        assertFalse("Abbrechen" in context, context)
        assertFalse("Which media" in context, context)
    }

    @Test
    fun `the key itself is never an example, its target value being the one replaced`() {
        val stale = wizard + ("wizard:review.generation.narration" to mapOf("en" to "Generate narration", "de" to "Erzeuge Erzählung"))
        assertFalse("Erzählung" in contextOf("wizard:review.generation.narration", stale))
    }

    @Test
    fun `placeholders and common words do not make two texts alike`() {
        val known = mapOf("a:files" to mapOf("en" to "{{count}} files for you", "de" to "{{count}} Dateien für dich"))
        assertEquals(TranslationContext.header(listOf("b:items")), contextOf("b:items", known, "{{count}} items for you"))
    }

    @Test
    fun `a flat key has no parent, the shared words still count`() {
        val flat = Config(flatKeys = true)
        val known = mapOf("wizard.generation.images" to mapOf("en" to "Generate images", "de" to "Bilder generieren"))
        val context = contextOf("wizard.generation.narration", known, "Generate narration", flat)
        assertFalse("same parent key" in context, context)
        assertTrue("Bilder generieren" in context, context)
    }

    @Test
    fun `a large namespace stays within the budget`() {
        val known = (1..500).associate { "big:group.key$it" to mapOf("en" to "Generate report number $it with a long tail of words", "de" to "Bericht Nummer $it erzeugen, mit vielen weiteren Wörtern") }
        val header = TranslationContext.header(listOf("big:group.new"))
        val context = contextOf("big:group.new", known, "Generate report")
        assertTrue(context.length - header.length <= TranslationContext.BUDGET, "${context.length - header.length}")
        assertTrue(context.length > header.length, "something still fits")
    }

    @Test
    fun `a batch gets each example once, none of its own keys`() {
        val keys = listOf("wizard:review.generation.narration", "wizard:review.generation.video")
        val context = TranslationContext.of(
            TranslationContext.header(keys), keys, listOf("Generate narration", "Generate video"), wizard, "en", "de", config
        )
        assertEquals(1, Regex("Bilder generieren").findAll(context).count(), context)
        assertTrue(context.startsWith("UI strings of an application, under the i18n keys, in order: "), context)
    }

    @Test
    fun `words are lower-cased, markup and short words left out`() {
        assertEquals(setOf("save", "changes"), TranslationContext.wordsOf("Save <b>{{n}}</b> changes to it"))
    }

    @Test
    fun `where the code shows the text follows the key sentence, before the examples`() {
        val key = "wizard:review.generation.narration"
        val header = TranslationContext.withRoles(TranslationContext.header(listOf(key)), listOf("caption of <SettingsOption>"))
        val context = TranslationContext.of(header, listOf(key), listOf("Generate narration"), wizard, "en", "de", config)

        assertTrue(
            context.startsWith(
                "A UI string of an application, under the i18n key $key. Shown in the interface as: caption of <SettingsOption>. " +
                    "Already translated under the same parent key: "
            ),
            context
        )
    }

    @Test
    fun `no role leaves the key sentence alone, and the roles are given once, the first ones only`() {
        val header = TranslationContext.header(listOf("wizard:settings.save"))
        assertEquals(header, TranslationContext.withRoles(header, emptyList()))
        assertEquals(header, TranslationContext.withRoles(header, listOf(" ", "")))
        assertEquals(
            "$header. Shown in the interface as: text of <h2>; label of <Button>",
            TranslationContext.withRoles(header, listOf("text of <h2>", "text of <h2>", "label of <Button>", "title of <Card>"))
        )
    }
}
