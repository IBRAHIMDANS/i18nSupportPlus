package com.ibrahimdans.i18n.extensions.localization.yaml

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A value written into a YAML file reads back as the very string written: `Note: soon` stays a
 * string instead of becoming a nested mapping, `Save # now` keeps its end instead of losing it to
 * a comment, and an edited value keeps its quotes and its line's comment.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class YamlValueWritingTest : PlatformBaseTest() {

    private val generator = YamlLocalization().contentGenerator()

    private val tricky = listOf(
        "Note: soon", "Save # now", "{{count}} items", "yes", "123", "It's", "\"quoted\"", "one\ntwo", " padded", "",
        "Plain value",
    )

    private var files = 0

    /**
     * A new file per call: configuring the same name again reparses the old document incrementally,
     * after which the IDE's YAML reader left a double-quoted scalar's escapes undecoded.
     */
    private fun configure(text: String): YAMLFile = myFixture.configureByText("de${files++}.yml", text) as YAMLFile

    private fun root(file: YAMLFile): YAMLMapping = file.documents.first().topLevelValue as YAMLMapping

    /** The value at [path] as the plugin reads it ([TranslationPsi.readValue]), quotes and escapes resolved. */
    private fun read(file: YAMLFile, vararg path: String): String? = keyValue(file, *path)?.value?.let(TranslationPsi::readValue)

    private fun keyValue(file: YAMLFile, vararg path: String): YAMLKeyValue? {
        var mapping: YAMLMapping? = root(file)
        var found: YAMLKeyValue? = null
        for (name in path) {
            found = mapping?.getKeyValueByKey(name) ?: return null
            mapping = found.value as? YAMLMapping
        }
        return found
    }

    private fun write(action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, action)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun insert(file: YAMLFile, container: PsiElement, value: String, vararg unresolved: String) = write {
        val literals = unresolved.map { Literal(it) }
        generator.generate(container, FullKey(unresolved.joinToString("."), null, literals), literals, value)
    }

    @Test
    fun `an inserted value reads back as written`() {
        for (value in tricky) {
            val file = configure("title: Titel\n")
            insert(file, root(file), value, "save")
            Assertions.assertEquals(value, read(file, "save"), "inserted ${'"'}$value${'"'}:\n${file.text}")
            Assertions.assertEquals("Titel", read(file, "title"))
        }
    }

    @Test
    fun `an inserted value under new parent keys reads back as written`() {
        for (value in tricky) {
            val file = configure("title: Titel\n")
            insert(file, root(file), value, "menu", "open")
            Assertions.assertEquals(value, read(file, "menu", "open"), "inserted ${'"'}$value${'"'}:\n${file.text}")
        }
    }

    @Test
    fun `a new file's content reads back as written`() {
        for (value in tricky) {
            val text = generator.generateContent(listOf(Literal("menu"), Literal("open")), value)
            val file = configure(text)
            Assertions.assertEquals(value, read(file, "menu", "open"), "generated:\n$text")
        }
    }

    @Test
    fun `an edited value reads back as written`() {
        for (value in tricky) {
            val file = configure("title: Titel\n")
            write { TranslationPsi.replaceValue(keyValue(file, "title")!!, value, project) }
            Assertions.assertEquals(value, read(file, "title"), "edited to ${'"'}$value${'"'}:\n${file.text}")
        }
    }

    @Test
    fun `an edited value keeps its quotes and its line's comment`() {
        val file = configure("# German\nsingle: 'Titel'   # shown in the header\ndouble: \"Zitat\"\nplain: Text\n")
        write {
            TranslationPsi.replaceValue(keyValue(file, "single")!!, "Neu", project)
            TranslationPsi.replaceValue(keyValue(file, "double")!!.value!!, "Neu", project)
            TranslationPsi.replaceValue(keyValue(file, "plain")!!, "Neu: Text", project)
        }
        Assertions.assertEquals(
            "# German\nsingle: 'Neu'   # shown in the header\ndouble: \"Neu\"\nplain: 'Neu: Text'\n",
            file.text
        )
    }

    @Test
    fun `a plain value is written without quotes`() {
        val file = configure("title: Titel\n")
        insert(file, root(file), "Speichern", "save")
        write { TranslationPsi.replaceValue(keyValue(file, "title")!!, "Neuer Titel", project) }
        Assertions.assertEquals("title: Neuer Titel\nsave: Speichern\n", file.text)
    }

    @Test
    fun `inserting into a mapping indented by four spaces leaves its lines as they were`() {
        val file = configure("title: Titel\nnested:\n    a: A\n")
        insert(file, keyValue(file, "nested")!!.value!!, "B", "b")
        Assertions.assertEquals("title: Titel\nnested:\n    a: A\n    b: B\n", file.text)
    }

    @Test
    fun `new parent keys inside an indented mapping sit under it`() {
        val file = configure("nested:\n    a: A\n")
        insert(file, keyValue(file, "nested")!!.value!!, "Note: soon", "menu", "open")
        Assertions.assertEquals("nested:\n    a: A\n    menu:\n      open: 'Note: soon'\n", file.text)
        Assertions.assertEquals("Note: soon", read(file, "nested", "menu", "open"))
    }

    @Test
    fun `a key sorted before the first one takes its indentation`() {
        val settings = Settings.getInstance(project)
        val sorted = settings.extractSorted
        settings.extractSorted = true
        try {
            val file = configure("nested:\n    m: M\n    z: Z\n")
            insert(file, keyValue(file, "nested")!!.value!!, "A", "a")
            insert(file, keyValue(file, "nested")!!.value!!, "P", "p")
            Assertions.assertEquals("nested:\n    a: A\n    m: M\n    p: P\n    z: Z\n", file.text)
        } finally {
            settings.extractSorted = sorted
        }
    }
}
