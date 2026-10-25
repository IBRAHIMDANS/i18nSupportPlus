package com.ibrahimdans.i18n.format

import com.ibrahimdans.i18n.format.TsCatalogTree.Reading
import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TsCatalogTreeTest {

    private fun parse(text: String): TsCatalogTree = TsCatalogTree.parse(text) ?: error("no catalogue in: $text")

    private fun Tree<ParsedNode>.names(): List<String> = entries()!!.map { it.first }

    private fun Tree<ParsedNode>.at(path: String): ParsedNode =
        path.split('.').fold(this) { node, name -> node.findChild(name) ?: error("no $name in $path") }.value()

    private fun String.at(span: TextSpan?): String = substring(span!!.start, span.end)

    @Test
    fun `each export form gives its object`() {
        val forms = listOf(
            "export default { title: 'Hello' }",
            "export default ({ title: 'Hello' })",
            "export const en = { title: 'Hello' }",
            "export const en = { title: 'Hello' } as const",
            "export const en = { title: 'Hello' } satisfies Messages",
            "const en = { title: 'Hello' } as const\nexport default en",
            "module.exports = { title: 'Hello' }",
        )
        for (form in forms) assertEquals("Hello", parse(form).at("title").value, form)
    }

    @Test
    fun `the default export wins over a named one`() {
        assertEquals("D", parse("export const other = { title: 'N' }\nexport default { title: 'D' }").at("title").value)
    }

    @Test
    fun `nested keys, identifier, string and number keys, quotes of every kind`() {
        val tree = parse(
            """
            export default {
              menu: { "open": "Open", 'close': 'Close', count: 1 },
              'with-dash': `Back tick`,
              2: 'two',
              list: ['a', 'b'],
              flag: true,
              nothing: null,
            }
            """.trimIndent()
        )
        assertEquals(listOf("menu", "with-dash", "2", "list", "flag", "nothing"), tree.names())
        assertEquals("Open", tree.at("menu.open").value)
        assertEquals("Close", tree.at("menu.close").value)
        assertEquals("1", tree.at("menu.count").value)
        assertEquals("Back tick", tree.at("with-dash").value)
        assertEquals("two", tree.at("2").value)
        assertEquals("['a', 'b']", tree.at("list").value)
        assertEquals("true", tree.at("flag").value)
        assertEquals("null", tree.at("nothing").value)
        assertTrue(tree.findChild("menu")!!.isTree())
    }

    @Test
    fun `strings are decoded from their escapes`() {
        val tree = parse("""export default { a: 'It\'s', b: "line\nnext é \u{1F600} \x41" }""")
        assertEquals("It's", tree.at("a").value)
        assertEquals("line\nnext é 😀 A", tree.at("b").value)
    }

    @Test
    fun `spans are in chars, quotes included, after non-ASCII text`() {
        val text = "// 日本語 et émojis 😀\nexport default { 'clé': 'Résumé', next: \"ok\" }"
        val tree = parse(text)
        val cle = tree.at("clé")
        assertEquals("'clé'", text.at(cle.keySpan))
        assertEquals("'Résumé'", text.at(cle.valueSpan))
        val next = tree.at("next")
        assertEquals("next", text.at(next.keySpan))
        assertEquals("\"ok\"", text.at(next.valueSpan))
    }

    @Test
    fun `what only running the code resolves is reported, with its line`() {
        val cases = mapOf(
            "import base from './base'\nexport default {\n  ...base,\n}" to Reading.Partial("spread", 3),
            "export default {\n  [key]: 'x' }" to Reading.Partial("computed key", 2),
            "export default { a: `Hi \${name}` }" to Reading.Partial("template with \${…}", 1),
            "export default { a: label }" to Reading.Partial("identifier value", 1),
            "export default { a: t('x') }" to Reading.Partial("call_expression value", 1),
            "export default { a: 'x' + 'y' }" to Reading.Partial("binary_expression value", 1),
            "export default { a, b: 'x' }" to Reading.Partial("shorthand property", 1),
            "export default { a() { return 'x' } }" to Reading.Partial("method", 1),
            "export default { nested: { deep: { ...x } } }" to Reading.Partial("spread", 1),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, TsCatalogTree.read(text), text)
            assertNull(TsCatalogTree.parse(text), text)
        }
    }

    @Test
    fun `a file exporting no object is no catalogue`() {
        val codes = listOf(
            "export function format(x: string) { return x }",
            "export default 'text'",
            "export { default } from './common'",
            "const local = { a: 'x' }",
            "export const en = { a: 'x' }\nexport const fr = { a: 'y' }",
            "",
        )
        for (code in codes) assertEquals(Reading.NoCatalogue, TsCatalogTree.read(code), code)
    }
}
