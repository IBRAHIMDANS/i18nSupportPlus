package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A computed property named by a string, `{ ['save']: … }`: the JS reference pattern asked that string for its
 * reference while its references were being computed, and the IDE reported a StackOverflowError.
 */
class ComputedStringKeyReferencesTest : PlatformBaseTest() {

    @Test
    fun `a string computed key is read without overflowing the stack`() {
        addFileToProject("locales/en/translation.json", """{"labels": {"save": "Save"}}""")
        myFixture.configureByText("labels.ts", "export const labels = { ['save']: 'Save', [`edit`]: 'Edit' };")

        val references = ReadAction.compute<Int, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).sumOf { it.references.size }
        }
        Assertions.assertTrue(references >= 0)
        myFixture.doHighlighting()
    }
}
