package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlElementTree
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [Tree.entries] is the single-walk form of "list the names, then [Tree.findChild] each one":
 * completion relies on both answering the same, so that is what is asserted here, per format.
 */
class TreeEntriesTest : PlatformBaseTest() {

    /** (name, value text) as findChildren + findChild see them — the reference [Tree.entries] must match. */
    private fun perName(tree: Tree<PsiElement>): List<Pair<String, String?>> =
        tree.findChildren("").map { it.value().text.unQuote() }
            .map { name -> name to tree.findChild(name)?.value()?.text }

    private fun walked(tree: Tree<PsiElement>): List<Pair<String, String?>> =
        tree.entries()!!.map { (name, node) -> name to node.value().text }

    @Test
    fun `json entries answer as findChild does, nested objects included`() {
        val file = myFixture.configureByText("en.json", """{"home": "Home", "menu": {"a": "A"}, "dotted.key": "D"}""")
        ReadAction.run<RuntimeException> {
            val tree = JsonElementTree.create(file)!!
            assertEquals(perName(tree), walked(tree))
            assertEquals(listOf("home", "menu", "dotted.key"), walked(tree).map { it.first })
        }
    }

    @Test
    fun `yaml entries answer as findChild does, nested mappings included`() {
        val file = myFixture.configureByText("en.yml", "home: Home\nmenu:\n  a: A\n'quoted': Q\n")
        ReadAction.run<RuntimeException> {
            val tree = YamlElementTree.create(file)!!
            assertEquals(perName(tree), walked(tree))
            assertEquals(listOf("home", "menu", "quoted"), walked(tree).map { it.first })
        }
    }

    @Test
    fun `a format without a single walk says so rather than answering empty`() {
        val leaf = object : Tree<String> {
            override fun findChild(name: String): Tree<String>? = null
            override fun isTree(): Boolean = false
            override fun value(): String = ""
            override fun findChildren(prefix: String): List<Tree<String>> = emptyList()
        }
        assertNull(leaf.entries(), "null sends the caller back to findChild; an empty list would hide every value")
    }
}
