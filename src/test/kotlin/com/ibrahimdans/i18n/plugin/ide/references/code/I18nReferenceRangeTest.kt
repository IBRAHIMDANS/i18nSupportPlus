package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The range a key's reference covers in its literal: the key, never the opening quote. */
class I18nReferenceRangeTest : PlatformBaseTest() {

    private fun referenceOf(literalText: String): Pair<JSLiteralExpression, I18nReference> =
        ApplicationManager.getApplication().runReadAction(Computable {
            val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).single { it.text == literalText }
            literal to literal.references.filterIsInstance<I18nReference>().single()
        })

    @Test
    fun `a key written as such covers the key`() {
        addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""")
        myFixture.configureByText("Plain.js", "export const a = (t) => t('test:ref.key');")

        val (literal, reference) = referenceOf("'test:ref.key'")

        Assertions.assertEquals("test:ref.key", reference.rangeInElement.substring(literal.text))
    }

    @Test
    fun `a key not written as such in the literal takes the literal's value, not its opening quote`() {
        addFileToProject("assets/test.json", """{"it's": "Value"}""")
        myFixture.configureByText("Escaped.js", "export const a = (t) => t('test:it\\'s');")

        val (literal, reference) = referenceOf("'test:it\\'s'")

        Assertions.assertEquals("test:it\\'s", reference.rangeInElement.substring(literal.text))
    }
}
