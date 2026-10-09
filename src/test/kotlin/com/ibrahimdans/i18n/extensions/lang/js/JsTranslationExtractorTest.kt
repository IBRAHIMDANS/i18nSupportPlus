package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The messages a JavaScript string makes: a literal, a template literal, a concatenation. */
class JsTranslationExtractorTest : PlatformBaseTest() {

    private val extractor = JsTranslationExtractor()

    /** The leaf at [marker] in [code], written in a `.ts` file. */
    private fun leafAt(code: String, marker: String): PsiElement {
        val file = myFixture.configureByText("labels.ts", code)
        return file.findElementAt(file.text.indexOf(marker))!!
    }

    private fun covered(element: PsiElement): String = extractor.textRange(element).substring(element.containingFile.text)

    @Test
    fun aTemplateLiteralPassesItsExpressions() {
        val leaf = leafAt("const label = (files) => `\${files.length} files in \${folder.name}`;", " files")
        assertTrue(extractor.canExtract(leaf))
        assertEquals("{{count}} files in {{name}}", extractor.text(leaf))
        assertEquals("`\${files.length} files in \${folder.name}`", covered(leaf))
        assertEquals(
            listOf(MessageVariable("count", "files.length", "{{count}}"), MessageVariable("name", "folder.name", "{{name}}")),
            extractor.variables(leaf)
        )
        assertEquals("i18n.t('k', { count: files.length, name: folder.name })", extractor.call(leaf)("'k'", extractor.variables(leaf)))
    }

    @Test
    fun aConcatenationIsOneMessage() {
        val leaf = leafAt("const label = 'Hello ' + user.name + '!';", "Hello")
        assertEquals("Hello {{name}}!", extractor.text(leaf))
        assertEquals("'Hello ' + user.name + '!'", covered(leaf))
        assertEquals("i18n.t('k', { name: user.name })", extractor.template(leaf)("'k'"))
    }

    /** `count + 1 + ' items'` adds before it concatenates: only the literal is a message. */
    @Test
    fun anAdditionIsNotAConcatenation() {
        val leaf = leafAt("const label = count + 1 + ' items';", " items")
        assertEquals(" items", extractor.text(leaf))
        assertEquals("' items'", covered(leaf))
        assertEquals(emptyList<MessageVariable>(), extractor.variables(leaf))
    }

    /** A call has no name to borrow: it reads `value`, and two of them `value`, `value2`. */
    @Test
    fun anExpressionWithoutNameReadsValue() {
        val leaf = leafAt("const label = `\${format(a)} to \${format(b)}`;", " to ")
        assertEquals("{{value}} to {{value2}}", extractor.text(leaf))
    }

    @Test
    fun aPlainLiteralIsUnchanged() {
        val leaf = leafAt("const label = 'Save';", "Save")
        assertEquals("Save", extractor.text(leaf))
        assertEquals("i18n.t('k')", extractor.template(leaf)("'k'"))
    }
}
