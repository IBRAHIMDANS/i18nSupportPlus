package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The msgid [LinguiTransKeyExtractor] builds from a `<Trans>` tag.
 *
 * Lingui numbers the expressions it extracts, so `<Trans>Hello {name}!</Trans>` is catalogued
 * under `Hello {0}!`. Reproducing the expression source (`{name}`) yields a key that matches
 * nothing, which is what the extractor used to do.
 */
class LinguiTransPlaceholderTest : PlatformBaseTest() {

    private fun msgidOf(transTag: String): String {
        myFixture.configureByText("test.jsx", "const C = () => ($transTag);")
        val tag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.name == "Trans" }
        val extractor = LinguiTransKeyExtractor()
        check(extractor.canExtract(tag as PsiElement)) { "the tag must be extractable" }
        return extractor.extract(tag).keyElements.joinToString("") { it.text }
    }

    @Test
    fun plainTextKeepsItsMsgid() {
        assertEquals("Hello world!", msgidOf("<Trans>Hello world!</Trans>"))
    }

    @Test
    fun oneExpressionBecomesPositionZero() {
        assertEquals("Hello {0}!", msgidOf("<Trans>Hello {name}!</Trans>"))
    }

    @Test
    fun expressionsAreNumberedInOrder() {
        assertEquals("{0} owes {1}", msgidOf("<Trans>{payer} owes {amount}</Trans>"))
    }

    @Test
    fun aNestedBraceIsConsumedWholeAsOnePlaceholder() {
        // Matching on the first `}` would cut here and emit a second, bogus placeholder.
        assertEquals("Total: {0}", msgidOf("<Trans>Total: {cart.items[0]}</Trans>"))
    }
}
