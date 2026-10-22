package com.ibrahimdans.i18n.plugin.tree

import com.ibrahimdans.i18n.LocalizationConfigImpl
import com.ibrahimdans.i18n.TranslationFormat
import com.ibrahimdans.i18n.TranslationSource
import com.ibrahimdans.i18n.plugin.format.JsonTranslationTree
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.format.YamlTranslationTree
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Key resolution on the trees the PSI-free parsers build: the same rules the plugin applies to
 * its PSI trees — composite keys, `*`, numeric and CLDR plurals — with no IDE running.
 */
class ParsedNodeKeyResolutionTest {

    private val resolver = object : CompositeKeyResolver<ParsedNode> {}

    private val format = object : TranslationFormat {
        override fun config() = LocalizationConfigImpl("test")
    }

    private fun json(text: String) = TranslationSource(JsonTranslationTree.parse(text), "en.json", "locales", "locales/en.json", format)

    private fun keyOf(vararg parts: String) = parts.map(::Literal)

    @Test
    fun `resolves a composite key to its leaf`() {
        val source = json("""{"menu": {"home": "Home", "about": "About"}}""")

        val ref = resolver.resolveCompositeKey(keyOf("menu", "about"), source)!!

        assertEquals("About", ref.element?.value()?.value)
        assertEquals(keyOf("menu", "about"), ref.path)
        assertTrue(ref.unresolved.isEmpty())
        assertEquals(source, ref.localizationSource)
    }

    @Test
    fun `keeps what it cannot resolve as unresolved`() {
        val ref = resolver.resolveCompositeKey(keyOf("menu", "contact", "title"), json("""{"menu": {"home": "Home"}}"""))!!

        assertEquals(keyOf("menu"), ref.path)
        assertEquals(keyOf("contact", "title"), ref.unresolved)
        assertNull(resolver.resolveCompositeKeyProperty(keyOf("menu", "contact", "title"), json("""{"menu": {"home": "Home"}}""")))
    }

    @Test
    fun `an intermediate wildcard expands to every child`() {
        val source = json("""{"pages": {"home": {"title": "Home"}, "about": {"title": "About"}}}""")

        val refs = resolver.resolveCompositeKeys(keyOf("pages", "*", "title"), source)

        assertEquals(listOf("Home", "About"), refs.map { it.element?.value()?.value })
    }

    @Test
    fun `a terminal wildcard stays on its parent`() {
        val ref = resolver.resolveCompositeKeys(keyOf("pages", "*"), json("""{"pages": {"home": "Home"}}""")).single()

        assertEquals("pages", ref.element?.value()?.key)
        assertTrue(ref.unresolved.isEmpty())
    }

    @Test
    fun `resolves the numeric plural forms`() {
        val source = json("""{"cart": {"item-1": "one item", "item-2": "two items", "item-5": "items"}}""")

        val refs = resolver.resolve(keyOf("cart", "item"), source, "-")

        assertEquals(listOf("one item", "two items", "items"), refs.map { it.element?.value()?.value })
        assertTrue(refs.all { it.isPlural && it.path == keyOf("cart", "item") })
    }

    @Test
    fun `resolves the CLDR plural forms`() {
        val source = json("""{"cart": {"item_one": "one item", "item_other": "{{count}} items"}}""")

        val refs = resolver.resolve(keyOf("cart", "item"), source, "-")

        assertEquals(listOf("one item", "{{count}} items"), refs.map { it.element?.value()?.value })
        assertTrue(refs.all { it.isPlural })
    }

    @Test
    fun `a key with no plural form stays unresolved`() {
        val ref = resolver.resolve(keyOf("cart", "item"), json("""{"cart": {"total": "Total"}}"""), "-").single()

        assertFalse(ref.isPlural)
        assertEquals(keyOf("item"), ref.unresolved)
    }

    @Test
    fun `lists the variants at a key position`() {
        val variants = resolver.listCompositeKeyVariants(keyOf("menu"), "a", json("""{"menu": {"about": "A", "account": "B", "home": "H"}}"""))

        assertEquals(listOf("about", "account"), variants.map { it.value().key })
    }

    @Test
    fun `resolves a YAML tree the same way`() {
        val tree = YamlTranslationTree.parse("menu:\n  item_one: one item\n  item_other: items\n")
        val source = TranslationSource(tree, "en.yml", "locales", "locales/en.yml", format)

        val refs = resolver.resolve(keyOf("menu", "item"), source, "-")

        assertEquals(listOf("one item", "items"), refs.map { it.element?.value()?.value })
    }
}
