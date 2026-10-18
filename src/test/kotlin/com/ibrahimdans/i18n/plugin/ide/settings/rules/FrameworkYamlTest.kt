package com.ibrahimdans.i18n.plugin.ide.settings.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameworkYamlTest {

    @Test
    fun readsScalarsAndBothListStyles() {
        val yaml = """
            ---
            languageIds:
              - javascript   # a comment
              - 'vue'
            # between keys
            refactorTemplates: ["${'$'}t('${'$'}1')", 'i18n.get(''${'$'}1'')', plain]
            monopoly: true
            name: my framework # trailing
            empty:
        """.trimIndent()

        assertEquals(
            mapOf(
                "languageIds" to listOf("javascript", "vue"),
                "refactorTemplates" to listOf("\$t('\$1')", "i18n.get('\$1')", "plain"),
                "monopoly" to true,
                "name" to "my framework",
                "empty" to emptyList<String>(),
            ),
            FrameworkYaml.parse(yaml)
        )
    }

    @Test
    fun unescapesDoubleQuotedStringsAndKeepsSingleQuotedOnesAsWritten() {
        val parsed = FrameworkYaml.parse("a: \"[^\\\\w\\\\d]t\\\\(['\\\"`]({key})\"\nb: '[^\\w\\d]t\\(# not a comment'")

        assertEquals("[^\\w\\d]t\\(['\"`]({key})", parsed["a"])
        assertEquals("[^\\w\\d]t\\(# not a comment", parsed["b"])
    }

    @Test
    fun rejectsWhatItCannotReadWithItsLine() {
        assertEquals(4, assertThrows<FrameworkYamlException> { FrameworkYaml.parse("a: 1\n\nb:\n  c: d") }.line)
        assertEquals(1, assertThrows<FrameworkYamlException> { FrameworkYaml.parse("- orphan") }.line)
        assertEquals(1, assertThrows<FrameworkYamlException> { FrameworkYaml.parse("a: [x, y") }.line)
        assertEquals(1, assertThrows<FrameworkYamlException> { FrameworkYaml.parse("a: \"bad \\q escape\"") }.line)
        assertEquals(1, assertThrows<FrameworkYamlException> { FrameworkYaml.parse("a: |\n  block") }.line)
    }
}
