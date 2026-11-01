package com.ibrahimdans.i18n.extract.lingui

import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The msgids the plugin's `LinguiTransKeyExtractor` builds (`LinguiTransHighlightingTest`, `LinguiTransPlaceholderTest`), on tree-sitter. */
class LinguiTransMsgidExtractorTest {

    private fun msgids(jsx: String): List<String> =
        findKeys(TreeSitterCodeNode.parseTsx("const C = () => <div>$jsx</div>;"), listOf(LinguiTransMsgidExtractor()))
            .map { (_, key) -> key.keyElements.joinToString("") { it.text } }

    @Test
    fun `source text is the msgid, marked as such`() {
        val key = findKeys(TreeSitterCodeNode.parseTsx("<Trans>Hello world!</Trans>"), listOf(LinguiTransMsgidExtractor())).single()
        assertEquals("Hello world!", key.key.keyElements.single().text)
        assertEquals(true, key.key.sourceText)
        assertEquals("jsx_element", key.node.type)
    }

    @Test
    fun `an identifier keeps its name, other expressions are numbered`() {
        assertEquals(listOf("Hello {name}, total {0} of {1}!"), msgids("<Trans>Hello {name}, total {formatPrice(42)} of {user.total}!</Trans>"))
    }

    @Test
    fun `a labelled expression takes its label, TypeScript casts are looked through`() {
        assertEquals(listOf("{name} and {total} and {n}"), msgids("<Trans>{ph({ name: getName() })} and {{ total: price() }} and {(n as string)!}</Trans>"))
    }

    @Test
    fun `elements are indexed in opening order, an empty one self-closed`() {
        assertEquals(listOf("Read the <0>docs</0> and <1/> then <2>more <3>here</3></2>"), msgids("<Trans>Read the <a href=\"x\">docs</a> and <br/> then <b>more <i>here</i></b></Trans>"))
    }

    @Test
    fun `a string literal is text and a comment is dropped without taking an index`() {
        assertEquals(listOf("Hi! {0}"), msgids("""<Trans>Hi{"!"} {/* note */}{f()}</Trans>"""))
    }

    @Test
    fun `text follows JSX's whitespace rule`() {
        assertEquals(listOf("Hello world, again"), msgids("<Trans>\n    Hello\n    world,\n\n    again\n  </Trans>"))
    }

    @Test
    fun `another tag, or an empty Trans, is no key`() {
        assertEquals(emptyList<String>(), msgids("<span>Hello</span><Trans></Trans>"))
    }
}
