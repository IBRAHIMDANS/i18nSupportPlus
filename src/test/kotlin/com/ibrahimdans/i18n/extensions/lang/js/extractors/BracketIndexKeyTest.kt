package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** `t(KEYS['save'])`: the string indexes an object the code reads, it is no translation key. */
class BracketIndexKeyTest : PlatformBaseTest() {

    private fun translations() =
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}, "save": "Save"}""")

    private fun literal(text: String): JSLiteralExpression =
        PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).first { it.text == text }

    private fun isKey(text: String): Boolean = literal(text).references.any { it is I18nReference }

    /** Each range the highlighting reports with a description, as `text: description`. */
    private fun reported(): List<String> {
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description != null }
            .map { "${text.substring(it.startOffset, it.endOffset)}: ${it.description}" }
    }

    @Test
    fun `the index of a member access passed to t is no key`() {
        translations()
        myFixture.configureByText(
            "Index.ts",
            """
            declare const KEYS: Record<string, any>;
            export const a = (t: (k: string) => string) => [t(KEYS['nothing.here']), t(KEYS['a']['b.c']), t(KEYS?.['d.e'])];
            """.trimIndent(),
        )

        Assertions.assertFalse(isKey("'nothing.here'"))
        Assertions.assertFalse(isKey("'b.c'"))
        Assertions.assertFalse(isKey("'d.e'"))
        Assertions.assertEquals(
            emptyList<String>(),
            reported().filter { it.contains("nothing.here") || it.contains("b.c") || it.contains("d.e") },
        )
    }

    @Test
    fun `a key written as a literal or a template is still a key`() {
        translations()
        myFixture.configureByText(
            "Literal.ts",
            """
            declare const KEYS: Record<string, string>;
            export const a = (t: (k: string) => string, x: boolean) =>
                [t('common:button.save'), t(`common:save`), t(x ? 'common:save' : KEYS['k'])];
            """.trimIndent(),
        )

        Assertions.assertTrue(isKey("'common:button.save'"))
        Assertions.assertTrue(isKey("'common:save'"))
        Assertions.assertFalse(isKey("'k'"))
    }
}
