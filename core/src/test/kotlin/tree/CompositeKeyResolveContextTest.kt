package tree

import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.ContextKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** i18next's `context` option: `t('friend', { context })` reads `friend_male`, `friend_female`. */
internal class CompositeKeyResolveContextTest {

    private val resolver: CompositeKeyResolver<String> = object : CompositeKeyResolver<String> {}

    private fun source(vararg keys: String) =
        localizationSource(root(TestTree("people", keys.map { TestTree(it) })))

    private fun resolve(context: ContextOption?, vararg keys: String) =
        resolver.resolve(listOf(Literal("people"), Literal("friend")), source(*keys), "-", context)

    private val keyPath = listOf(Literal("people"), Literal("friend"))

    @Test
    fun `a dynamic context resolves onto every variant, under the key's own path`() {
        val refs = resolve(ContextOption.Dynamic, "friend_male", "friend_female", "other")
        assertEquals(listOf("friend_male", "friend_female"), refs.map { it.element?.value() })
        assertTrue(refs.all { it.unresolved.isEmpty() && it.path == keyPath })
    }

    @Test
    fun `a dynamic context also reads the key itself and its plural forms, once each`() {
        val refs = resolve(ContextOption.Dynamic, "friend_one", "friend_other", "friend_male")
        assertEquals(listOf("friend_one", "friend_other", "friend_male"), refs.map { it.element?.value() })
    }

    @Test
    fun `a dynamic context with no variant and no key stays unresolved on the key`() {
        val refs = resolve(ContextOption.Dynamic, "other")
        assertEquals(listOf(listOf(Literal("friend"))), refs.map { it.unresolved })
    }

    @Test
    fun `a fixed context reads its variant, plural forms included`() {
        assertEquals(listOf("friend_male"), resolve(ContextOption.Fixed("male"), "friend", "friend_male", "friend_female").map { it.element?.value() })
        val plural = resolve(ContextOption.Fixed("male"), "friend_male_one", "friend_male_other")
        assertEquals(listOf("friend_male_one", "friend_male_other"), plural.map { it.element?.value() })
        assertTrue(plural.all { it.path == keyPath })
    }

    @Test
    fun `a fixed context falls back to the key, as i18next does`() {
        assertEquals(listOf("friend"), resolve(ContextOption.Fixed("other"), "friend", "friend_male").map { it.element?.value() })
    }

    @Test
    fun `a fixed context with neither variant nor key reports the variant missing`() {
        val refs = resolve(ContextOption.Fixed("other"), "friend_male")
        assertEquals(listOf(listOf(Literal("friend_other"))), refs.map { it.unresolved })
    }

    @Test
    fun `no context option never reads a variant`() {
        val refs = resolve(null, "friend_male")
        assertEquals(listOf(listOf(Literal("friend"))), refs.map { it.unresolved })
    }

    @Test
    fun `the variant spelling and its reading`() {
        assertEquals("friend_male", ContextKey.variant("friend", "male"))
        assertEquals("friend" to "male", ContextKey.split("friend_male"))
        assertEquals("best_friend" to "male", ContextKey.split("best_friend_male"))
        assertNull(ContextKey.split("friend"))
        assertNull(ContextKey.split("_male"))
        assertNull(ContextKey.split("friend_"))
        assertTrue(ContextKey.reads(ContextOption.Dynamic, "male"))
        assertTrue(ContextKey.reads(ContextOption.Fixed("male"), "male"))
        assertTrue(!ContextKey.reads(ContextOption.Fixed("female"), "male"))
        assertTrue(!ContextKey.reads(null, "male"))
    }
}
