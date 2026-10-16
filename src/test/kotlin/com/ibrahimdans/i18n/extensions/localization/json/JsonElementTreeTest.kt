package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * A Paraglide (inlang) message file starts with `"$schema": "…"`: a JSON Schema convention, never a
 * translation key. It used to show in the tool window, the completion and the statistics.
 */
class JsonElementTreeTest : PlatformBaseTest() {

    private val paraglide = "{\"\$schema\": \"https://inlang.com/schema/inlang-message-format\", \"hello\": \"Hello\", \"bye\": \"Bye\"}"

    private fun <T> treeOf(content: String, block: (JsonElementTree) -> T): T {
        val file = myFixture.configureByText("en.json", content)
        return ReadAction.compute<T, RuntimeException> { block(JsonElementTree.create(file)!!) }
    }

    @Test
    fun theRootSchemaIsNoKey() {
        assertEquals(listOf("hello", "bye"), treeOf(paraglide) { tree -> tree.entries().map { it.first } })
        assertEquals(2, treeOf(paraglide) { it.findChildren("").size })
        assertEquals(0, treeOf(paraglide) { it.findChildren("\$").size })
    }

    /** Asked by name, it still resolves: nothing reads a key the code does not write. */
    @Test
    fun theRootSchemaStillResolvesByName() {
        treeOf(paraglide) { assertNotNull(it.findChild("\$schema")) }
    }

    @Test
    fun aNestedSchemaIsAKey() {
        val names = treeOf("{\"form\": {\"\$schema\": \"x\", \"name\": \"Name\"}}") { tree ->
            tree.findChild("form")!!.entries()!!.map { it.first }
        }
        assertEquals(listOf("\$schema", "name"), names)
    }

    @Test
    fun aFileWithoutSchemaIsUnchanged() {
        assertEquals(listOf("a", "b"), treeOf("{\"a\": \"1\", \"b\": {\"c\": \"2\"}}") { tree -> tree.entries().map { it.first } })
    }

    /** The readers walking the PSI themselves — inlays, *Missing key*, type generation — leave it out too. */
    @Test
    fun theLeavesOfTheFileLeaveItOut() {
        val file = myFixture.configureByText("en.json", paraglide)
        val leaves = ReadAction.compute<Set<List<String>>, RuntimeException> { TranslationFileKeys.translationLeaves(file).keys }
        assertEquals(setOf(listOf("hello"), listOf("bye")), leaves)
    }
}
