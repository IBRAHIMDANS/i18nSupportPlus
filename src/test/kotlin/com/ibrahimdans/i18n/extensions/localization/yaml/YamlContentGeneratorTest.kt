package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFile
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The keys written into a YAML file read back as typed, like the values ([YamlValueWritingTest]):
 * a segment holding `: ` or ` #` stays one key instead of splitting into a nested one or losing its
 * end to a comment, and one starting with a quote or `- ` parses. A plain segment stays plain.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class YamlContentGeneratorTest : PlatformBaseTest() {

    private val generator = YamlLocalization().contentGenerator()

    private var files = 0

    /** A new file per call, as [YamlValueWritingTest] does: reparsing a name incrementally leaves escapes undecoded. */
    private fun configure(text: String): YAMLFile = myFixture.configureByText("keys${files++}.yml", text) as YAMLFile

    private fun root(file: YAMLFile): YAMLMapping = file.documents.first().topLevelValue as YAMLMapping

    /** The value at [path] as the plugin reads it, found by the keys' decoded text; null when a key does not parse. */
    private fun read(file: YAMLFile, vararg path: String): String? {
        var mapping: YAMLMapping? = root(file)
        var found: YAMLKeyValue? = null
        for (name in path) {
            found = mapping?.getKeyValueByKey(name) ?: return null
            mapping = found.value as? YAMLMapping
        }
        return found?.value?.let(TranslationPsi::readValue)
    }

    private fun insert(file: YAMLFile, value: String, vararg unresolved: String) {
        val literals = unresolved.map { Literal(it) }
        WriteCommandAction.runWriteCommandAction(project) {
            generator.generate(root(file), FullKey(unresolved.joinToString("."), null, literals), literals, value)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    @Test
    fun testGenerateWithEmptyUnresolved_doesNotThrow() {
        myFixture.configureByText("test.yml", "key: value\n")
        val element = myFixture.file
        val fullKey = FullKey("key", null, listOf(Literal("key")))
        assertDoesNotThrow {
            generator.generate(element, fullKey, emptyList(), null)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["note: soon", "save # now", "'quoted'", "\"quoted\"", "- item", "say \"hi\"", " padded", "yes"])
    fun `a new file with a special key segment reads back as typed`(segment: String) {
        val content = generator.generateContent(listOf(Literal(segment), Literal("leaf")), "value")

        val file = configure(content)

        Assertions.assertEquals("value", read(file, segment, "leaf"), "generated:\n$content")
    }

    /** Existing fixtures and files keep their spelling: a key YAML reads plain is not quoted. */
    @Test
    fun `plain key segments stay plain`() {
        Assertions.assertEquals("user:\n  name: Name", generator.generateContent(listOf(Literal("user"), Literal("name")), "Name"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["note: soon", "save # now", "'quoted'", "- item"])
    fun `a special key segment added to an existing file reads back as typed`(segment: String) {
        val flat = configure("title: Titel\n")
        insert(flat, "value", segment)
        Assertions.assertEquals("value", read(flat, segment), flat.text)
        Assertions.assertEquals("Titel", read(flat, "title"), flat.text)

        val nested = configure("title: Titel\n")
        insert(nested, "value", segment, "leaf")
        Assertions.assertEquals("value", read(nested, segment, "leaf"), nested.text)
    }

    /**
     * `i18n-check.json` asks for sorted insertion, the stored setting left unchecked: the key lands
     * in its place. It used to go to the end of the file, the stored setting alone being read.
     */
    @Test
    fun `a key lands in order when the shared config file asks for it`() {
        myFixture.addFileToProject(ProjectConfigFile.PATH, """{ "extractSorted": true }""")
        try {
            val file = configure("apple: A\ncherry: C\n")
            insert(file, "B", "banana")
            Assertions.assertEquals(listOf("apple", "banana", "cherry"), root(file).keyValues.map { it.keyText }, file.text)
        } finally {
            val config = project.guessProjectDir()?.findChild(ProjectConfigFile.PATH)
            if (config != null) WriteAction.runAndWait<Exception> { config.delete(this) }
        }
    }
}
