package tree

import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * i18next's ordinal plurals: `t('place', { count, ordinal: true })` reads `place_ordinal_one`,
 * `place_ordinal_two`, `place_ordinal_few` or `place_ordinal_other`, never `place` itself.
 */
internal class CompositeKeyResolveOrdinalPluralsTest {

    private val resolver: CompositeKeyResolver<String> = object : CompositeKeyResolver<String> {}

    private fun resolvedForms(vararg leaves: String, key: String = "place"): List<String> {
        val ls = localizationSource(root(TestTree("race", leaves.map { TestTree(it) })))
        val references = resolver.resolve(listOf(Literal("race"), Literal(key)), ls, "-")
        references.forEach {
            assertTrue(it.unresolved.isEmpty(), "unresolved: ${it.unresolved}")
            assertEquals(listOf(Literal("race"), Literal(key)), it.path)
        }
        return references.map { it.element?.value().toString() }
    }

    @Test
    fun `a key held only by its ordinal forms is resolved`() {
        assertEquals(
            listOf("place_ordinal_one", "place_ordinal_other", "place_ordinal_two", "place_ordinal_few"),
            resolvedForms("place_ordinal_one", "place_ordinal_two", "place_ordinal_few", "place_ordinal_other"),
        )
    }

    @Test
    fun `cardinal and ordinal forms of the same key are both read, cardinal first`() {
        assertEquals(
            listOf("item_one", "item_other", "item_ordinal_one", "item_ordinal_other"),
            resolvedForms("item_ordinal_one", "item_one", "item_ordinal_other", "item_other", key = "item"),
        )
    }

    @Test
    fun `a key named like the infix, without a category, is no plural`() {
        val ls = localizationSource(root(TestTree("race", listOf(TestTree("place_ordinal")))))
        val references = resolver.resolve(listOf(Literal("race"), Literal("place")), ls, "-")
        assertEquals(listOf(listOf(Literal("place"))), references.map { it.unresolved })
    }
}
