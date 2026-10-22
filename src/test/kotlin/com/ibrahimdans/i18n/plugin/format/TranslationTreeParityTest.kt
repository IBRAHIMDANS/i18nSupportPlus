package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlElementTree
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The parsers that read a translation file without the PSI see what the PSI trees see: for every
 * JSON and YAML fixture of the plugin's tests, the same keys in the same order, the same trees and
 * leaves, the same leaf values. Only the fixtures named `invalid…` may be refused by the parser,
 * where the PSI still builds a tree around the error.
 */
class TranslationTreeParityTest : PlatformBaseTest() {

    private val fixtures: List<File> =
        File("src/test/resources").walk()
            .filter { it.isFile && it.extension in setOf("json", "yml", "yaml") }
            .sortedBy { it.path }
            .toList()

    /** Every node below [tree] as (path of names, value): null for a tree, the text of a leaf. */
    private fun <T> flatten(tree: Tree<T>, value: (T) -> String?, path: List<String> = emptyList()): List<Pair<List<String>, String?>> =
        tree.entries()!!.flatMap { (name, child) ->
            val childPath = path + name
            if (child.isTree()) listOf(childPath to null) + flatten(child, value, childPath)
            else listOf(childPath to value(child.value()))
        }

    private fun psiTree(file: PsiFile): Tree<PsiElement>? =
        if (file.name.endsWith(".json")) JsonElementTree.create(file) else YamlElementTree.create(file)

    private fun parsedTree(name: String, text: String): Tree<ParsedNode>? =
        if (name.endsWith(".json")) JsonTranslationTree.parse(text) else YamlTranslationTree.parse(text)

    @Test
    fun `every fixture reads the same with and without the PSI`() {
        assertTrue(fixtures.isNotEmpty(), "no fixture found from ${File("").absolutePath}")
        val mismatches = fixtures.withIndex().mapNotNull { (index, fixture) ->
            val text = fixture.readText()
            // An index in the name: fixtures from different folders share their file names.
            val name = "parity$index.${fixture.extension}"
            val parsed = parsedTree(name, text)
                ?: return@mapNotNull if (fixture.name.startsWith("invalid")) null else "${fixture.path}: refused by the parser"
            val file = myFixture.configureByText(name, text)
            ReadAction.compute<String?, RuntimeException> {
                val psi = psiTree(file) ?: return@compute "${fixture.path}: no PSI tree"
                val expected = flatten(psi, TranslationPsi::readValue)
                val actual = flatten(parsed, ParsedNode::value)
                if (expected == actual) null else "${fixture.path}:\n  PSI    $expected\n  parsed $actual"
            }
        }
        assertEquals(emptyList<String>(), mismatches, mismatches.joinToString("\n"))
    }
}
