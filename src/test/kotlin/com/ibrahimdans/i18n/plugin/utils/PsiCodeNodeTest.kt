package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.extensions.lang.js.JsxLang
import com.ibrahimdans.i18n.extensions.lang.js.extractors.DefineMessagesExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The PSI side of [CodeNode]: what [PsiCodeNode] reads of an element, and the PSI extractors and
 * languages answering the same through it as through the element.
 */
class PsiCodeNodeTest : PlatformBaseTest() {

    private fun literal(code: String, text: String): JSLiteralExpression {
        myFixture.configureByText("test.tsx", code)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).first { it.text == text }
    }

    /** A node no PSI stands behind, as another parser would hand one over. */
    private val foreign = object : CodeNode {
        override val type = "string"
        override val text = "'a.b'"
        override val span = TextSpan(2, 7)
        override val parent: CodeNode? = null
        override val children = emptyList<CodeNode>()
        override val file: String? = null
    }

    @Test
    fun testTheNodeReadsTheTextSpanAndFileOfItsElement() {
        val element = literal("t('menu.home')", "'menu.home'")
        val node = PsiCodeNode(element)

        assertEquals("'menu.home'", node.text)
        assertEquals(TextSpan(element.textRange.startOffset, element.textRange.endOffset), node.span)
        assertEquals(element.node.elementType.toString(), node.type)
        assertTrue(node.file!!.endsWith("test.tsx"))
    }

    @Test
    fun testTheParentsStopAtTheFile() {
        val ancestors = generateSequence(PsiCodeNode(literal("t('menu.home')", "'menu.home'")) as CodeNode) { it.parent }.toList()

        assertTrue((ancestors.last() as PsiCodeNode).element is PsiFile)
        assertNull(ancestors.last().parent)
    }

    @Test
    fun testTheChildrenKeepTheTokensInSourceOrder() {
        val arguments = PsiCodeNode(literal("t('menu.home', 2)", "'menu.home'")).parent!!

        assertEquals(listOf("(", "'menu.home'", ",", " ", "2", ")"), arguments.children.map { it.text })
    }

    @Test
    fun testTwoNodesOfTheSameElementAreEqual() {
        val element = literal("t('menu.home')", "'menu.home'")
        val node = PsiCodeNode(element)

        assertEquals(node, PsiCodeNode(element))
        assertEquals(node.hashCode(), PsiCodeNode(element).hashCode())
        assertTrue(node in node.parent!!.children)
    }

    @Test
    fun testAKeyExtractorAnswersANodeAsItsElement() {
        val extractor = DefineMessagesExtractor()
        val element = literal("const m = defineMessage({ id: \"app.greeting\", defaultMessage: \"Hello\" });", "\"app.greeting\"")
        val node = PsiCodeNode(element)

        assertTrue(extractor.canExtract(node))
        assertEquals(extractor.extract(element), extractor.extract(node))
        assertFalse(extractor.canExtract(PsiCodeNode(literal("defineMessage({ id: \"a\", defaultMessage: \"Hello\" })", "\"Hello\""))))
    }

    @Test
    fun testAKeyExtractorReadsNoNodeOutsideThePsi() {
        val extractor = DefineMessagesExtractor()

        assertFalse(extractor.canExtract(foreign))
        assertNull(extractor.extract(foreign))
    }

    @Test
    fun testALanguageAnswersANodeAsItsElement() {
        val lang = JsxLang()
        val names = listOf("t")
        val element = literal("t('menu.home')", "'menu.home'")
        val node = PsiCodeNode(element)

        assertEquals(lang.canExtractKey(element, names), lang.canExtractKey(node, names))
        assertEquals(lang.extractRawKey(element), lang.extractRawKey(node))
        assertFalse(lang.canExtractKey(foreign, names))
        assertNull(lang.extractRawKey(foreign))
    }
}
