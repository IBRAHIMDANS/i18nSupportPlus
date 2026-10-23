package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.tree.Tree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [TranslationLeaves] over the trees `core` parses itself, without the PSI. */
class TranslationLeavesTest {

    /** A parsed tree lists the child nodes themselves: the name is the node's key, the value its text. */
    private object ParsedLeafReader : LeafReader<ParsedNode> {
        override fun nameOf(node: Tree<ParsedNode>): String? = node.value().key
        override fun valueOf(node: Tree<ParsedNode>): String = node.value().value.orEmpty()
    }

    private fun leaves(
        tree: Tree<ParsedNode>,
        config: Config = Config(),
        nsPrefix: String = "",
        locale: String = "en",
        into: MutableMap<String, MutableMap<String, String>> = mutableMapOf(),
    ): Map<String, Map<String, String>> {
        TranslationLeaves.collect(config, tree, nsPrefix, locale, ParsedLeafReader, into)
        return into
    }

    @Test
    fun `nested keys are joined with the key separator`() {
        val tree = JsonTranslationTree.parse("""{"menu": {"home": "Home", "about": {"title": "About"}}, "ok": "OK"}""")!!
        assertEquals(
            mapOf(
                "menu.home" to mapOf("en" to "Home"),
                "menu.about.title" to mapOf("en" to "About"),
                "ok" to mapOf("en" to "OK"),
            ),
            leaves(tree),
        )
    }

    @Test
    fun `the namespace prefix is prepended to every key`() {
        val tree = JsonTranslationTree.parse("""{"title": "Title", "a": {"b": "B"}}""")!!
        assertEquals(setOf("common:title", "common:a.b"), leaves(tree, nsPrefix = "common:").keys)
    }

    @Test
    fun `a flat key is a single level whatever it contains`() {
        val tree = JsonTranslationTree.parse("""{"app.title": "App"}""")!!
        assertEquals(mapOf("app.title" to mapOf("en" to "App")), leaves(tree, Config(keySeparator = "|")))
    }

    @Test
    fun `two locales of the same key land in one entry`() {
        val into = mutableMapOf<String, MutableMap<String, String>>()
        leaves(JsonTranslationTree.parse("""{"hello": "Hello"}""")!!, locale = "en", into = into)
        leaves(YamlTranslationTree.parse("hello: Bonjour\n")!!, locale = "fr", into = into)
        assertEquals(mapOf("hello" to mapOf("en" to "Hello", "fr" to "Bonjour")), into)
    }

    @Test
    fun `a blank value is kept as such`() {
        val tree = JsonTranslationTree.parse("""{"empty": ""}""")!!
        assertEquals(mapOf("empty" to mapOf("en" to "")), leaves(tree))
    }
}
