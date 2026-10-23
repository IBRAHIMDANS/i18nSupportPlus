package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNodeTree
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.ide.actions.JsonKeySorter
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.deletePropertyAndSeparator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [TranslationEditor] writes what the PSI writes: each case runs the PSI path the plugin takes
 * today and the text edits of the editor on the same file, and compares the results. The
 * fixtures are those of `JsonInsertionLayoutTest`, `SortI18nKeysActionTest` and `GeneratedIndentTest`.
 *
 * A deletion is compared by content: the PSI leaves the whitespace around a deleted property, the
 * editor takes it with the property.
 */
class TranslationEditorParityTest : PlatformBaseTest() {

    private val sorted = KeyOrder.IGNORE_CASE.comparator

    private var files = 0

    /** [text], in a file of [extension], after [change] ran on its PSI in a write command. */
    private fun psi(extension: String, text: String, change: (PsiElement) -> Unit): String {
        // A fresh name each time: the fixture would not configure the same file twice.
        val file = myFixture.configureByText("en${files++}.$extension", text)
        WriteCommandAction.runWriteCommandAction(project) { change(file) }
        return file.text
    }

    private fun core(text: String, edits: List<TextEdit>): String = TextEdit.apply(text, edits)

    // --- JSON ---

    /** Adds [path] the way the dialog and *Fill Missing* do: under the deepest object of it there is. */
    private fun psiJsonInsert(text: String, path: List<String>, value: String): String = psi("json", text) { file ->
        var obj = (file as JsonFile).topLevelValue as JsonObject
        var depth = 0
        while (depth < path.size) {
            obj = obj.findProperty(path[depth])?.value as? JsonObject ?: break
            depth++
        }
        val literals = path.map { Literal(it) }
        val generator = JsonLocalization().contentGenerator()
        generator.generateTranslationEntry(obj, literals[depth].text, generator.generateContent(literals.drop(depth + 1), value))
    }

    private fun assertJsonInsert(text: String, key: String, value: String, order: Comparator<String>? = null) {
        val path = key.split('.')
        assertEquals(psiJsonInsert(text, path, value), core(text, JsonTranslationEditor.insert(text, path, value, order)))
    }

    @Test
    fun jsonInsertionKeepsTheLayoutOfTheFile() {
        assertJsonInsert("{\n  \"a\": \"A\"\n}", "b", "B")
        assertJsonInsert("{\n    \"a\": \"A\"\n}", "b", "B")
        assertJsonInsert("{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\"\n\t}\n}", "n.y", "Y")
        assertJsonInsert("{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\"\n\t}\n}", "b", "B")
        assertJsonInsert("{ \"a\": \"A\" }", "b", "B")
        assertJsonInsert("{\n    \"title\":   \"Titel\",\n    \"nested\": { \"a\": \"A-de\" }\n}", "nested.b", "B-de")
        assertJsonInsert("{\n    \"title\":   \"Titel\"\n}", "save.label.short", "Save")
        assertJsonInsert("{\n    \"nested\": {\n        \"a\": \"A\"\n    }\n}", "nested.b", "B")
        assertJsonInsert("{\n    \"a\": \"A\",\n    \"n\": {}\n}", "n.x", "X")
        assertJsonInsert("{}", "a", "A")
        assertJsonInsert("{\n    \"title\":   \"Titel\",\n\n    \"quoted\" : \"Zitat\",\n    \"n\": {\"x\":\"X\"}\n}", "z", "Z")
        assertJsonInsert("{\"a\": \"A\"}", "n.x", "X")
    }

    @Test
    fun jsonSortedInsertionMatches() = myFixture.runWithConfig(Config(extractSorted = true)) {
        assertJsonInsert("{\n    \"b\": \"B\",\n    \"c\": \"C\"\n}", "a", "A", sorted)
        assertJsonInsert("{\n    \"a\": \"A\",\n    \"c\": \"C\"\n}", "b", "B", sorted)
    }

    @Test
    fun jsonInsertionThenSortMatchesSortKeysAlphabetically() = myFixture.runWithConfig(Config(sortKeysAlphabetically = true)) {
        val text = "{\n    \"a\":   \"A\",\n    \"c\": \"C\"\n}"
        val inserted = core(text, JsonTranslationEditor.insert(text, listOf("b"), "B"))
        assertEquals(psiJsonInsert(text, listOf("b"), "B"), core(inserted, JsonTranslationEditor.sort(inserted, sorted)))
    }

    private fun assertJsonSort(text: String, order: KeyOrder = KeyOrder.IGNORE_CASE) = myFixture.runWithConfig(Config(keyOrder = order)) {
        val expected = psi("json", text) { JsonKeySorter.sortWithPsi(it as JsonFile, project, order.comparator) }
        assertEquals(expected, core(text, JsonTranslationEditor.sort(text, order.comparator)))
    }

    @Test
    fun jsonSortMatches() {
        assertJsonSort("""{"banana": "1", "apple": "2", "cherry": "3"}""")
        assertJsonSort("""{"Zebra": "1", "apple": "2", "Banana": "3"}""")
        assertJsonSort("""{"apple": "1", "Zebra": "2", "banana": "3", "Banana": "4"}""", KeyOrder.CHARACTER_CODE)
        assertJsonSort("""{"root": {"b": "1", "A": "2", "a": "3"}}""", KeyOrder.CHARACTER_CODE)
        assertJsonSort("""{"root": {"y": "1", "x": "2", "z": "3"}}""")
        assertJsonSort("{\n    \"c\":   \"C\",\n  \"b\": {\n    \"y\": 2,\n    \"x\": 1\n  },\n  \"a\": \"A\"\n}")
        assertJsonSort("{\n  \"\$schema\": \"s\",\n  \"b\": \"B\",\n  \"a\": \"A\"\n}")
    }

    @Test
    fun jsonRenameMatches() {
        val text = "{\n  \"menu\": {\n    \"save\": \"Save\"\n  }\n}"
        val expected = psi("json", text) { file ->
            val menu = ((file as JsonFile).topLevelValue as JsonObject).findProperty("menu")!!.value as JsonObject
            menu.findProperty("save")!!.setName("store")
        }
        assertEquals(expected, core(text, JsonTranslationEditor.rename(text, listOf("menu", "save"), "store")))
    }

    private fun assertJsonDelete(text: String, key: String) {
        val path = key.split('.')
        val expected = psi("json", text) { file ->
            var obj = (file as JsonFile).topLevelValue as JsonObject
            path.dropLast(1).forEach { obj = obj.findProperty(it)!!.value as JsonObject }
            deletePropertyAndSeparator(obj.findProperty(path.last())!!)
        }
        val actual = core(text, JsonTranslationEditor.delete(text, path))
        assertEquals(leaves(JsonTranslationTree.parse(expected)!!), leaves(JsonTranslationTree.parse(actual)!!))
    }

    @Test
    fun jsonDeleteMatches() {
        assertJsonDelete("""{"a":"1","b":"2","c":"3"}""", "b")
        assertJsonDelete("""{"a":"1","b":"2"}""", "a")
        assertJsonDelete("""{"x":"9","y":"8"}""", "y")
        assertJsonDelete("{\n  \"a\": \"1\",\n  \"n\": {\n    \"x\": \"X\",\n    \"y\": \"Y\"\n  }\n}", "n.x")
        assertJsonDelete("{\n  \"a\": \"1\",\n  \"n\": {\n    \"x\": \"X\"\n  }\n}", "n")
    }

    // --- YAML ---

    private fun psiYamlInsert(text: String, path: List<String>, value: String): String = psi("yml", text) { file ->
        var mapping = (file as YAMLFile).documents.first().topLevelValue as YAMLMapping
        var depth = 0
        while (depth < path.size) {
            mapping = mapping.getKeyValueByKey(path[depth])?.value as? YAMLMapping ?: break
            depth++
        }
        val literals = path.map { Literal(it) }
        val generator = YamlLocalization().contentGenerator()
        generator.generateTranslationEntry(mapping, literals[depth].text, generator.generateContent(literals.drop(depth + 1), value))
    }

    private fun assertYamlInsert(text: String, key: String, value: String, order: Comparator<String>? = null) {
        val path = key.split('.')
        assertEquals(psiYamlInsert(text, path, value), core(text, YamlTranslationEditor().insert(text, path, value, order)))
    }

    @Test
    fun yamlInsertionMatches() {
        assertYamlInsert("x: y\n", "a.b.c", "v")
        assertYamlInsert("a: A\n", "b", "B")
        assertYamlInsert("menu:\n    a: A\nz: Z\n", "menu.b", "B")
        assertYamlInsert("menu:\n  a: A\n", "menu.save.label", "Save")
        assertYamlInsert("a:\n  x: X\n  y: Y\n", "b", "B")
        assertYamlInsert("a: A\n", "b", "Note: soon")
        assertYamlInsert("a: A\n", "b", "yes")
    }

    @Test
    fun yamlSortedInsertionMatches() = myFixture.runWithConfig(Config(extractSorted = true)) {
        assertYamlInsert("menu:\n  b: B\n  c: C\n", "menu.a", "A", sorted)
        assertYamlInsert("a: A\nc: C\n", "b", "B", sorted)
    }

    @Test
    fun yamlRenameMatches() {
        val text = "button:\n  save: Save\n  cancel: Cancel\n"
        val expected = psi("yml", text) { file ->
            val button = ((file as YAMLFile).documents.first().topLevelValue as YAMLMapping).getKeyValueByKey("button")!!.value as YAMLMapping
            button.getKeyValueByKey("save")!!.setName("store")
        }
        assertEquals(expected, core(text, YamlTranslationEditor().rename(text, listOf("button", "save"), "store")))
    }

    @Test
    fun yamlDeleteMatches() {
        val text = "a: A\nmenu:\n  x: X\n  y: Y\nz: Z\n"
        listOf("a", "menu", "menu.x", "menu.y", "z").forEach { key ->
            val path = key.split('.')
            val expected = psi("yml", text) { file ->
                var mapping = (file as YAMLFile).documents.first().topLevelValue as YAMLMapping
                path.dropLast(1).forEach { mapping = mapping.getKeyValueByKey(it)!!.value as YAMLMapping }
                deletePropertyAndSeparator(mapping.getKeyValueByKey(path.last())!!)
            }
            val actual = core(text, YamlTranslationEditor().delete(text, path))
            assertEquals(leaves(YamlTranslationTree.parse(expected)!!), leaves(YamlTranslationTree.parse(actual)!!), key)
        }
    }

    /** Every leaf of [tree] by its path. */
    private fun leaves(tree: ParsedNodeTree, prefix: List<String> = emptyList()): Map<List<String>, String?> =
        tree.children().fold(emptyMap()) { acc, child ->
            val path = prefix + child.value().key.orEmpty()
            acc + if (child.isTree()) leaves(child, path) else mapOf(path to child.value().value)
        }
}
