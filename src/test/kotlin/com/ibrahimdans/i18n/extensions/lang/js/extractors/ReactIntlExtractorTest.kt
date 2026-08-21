package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit-level checks on the react-intl message descriptor extractor, independent of the
 * annotator pipeline.
 */
class ReactIntlExtractorTest : PlatformBaseTest() {

    private val extractor = ReactIntlExtractor()

    private fun literals(code: String): List<JSLiteralExpression> {
        myFixture.configureByText("test.tsx", code)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).toList()
    }

    /** Maps every string literal in [code] to whether the extractor claims it. */
    private fun extractionMap(code: String): Map<String, Boolean> =
        literals(code).associate { it.text to extractor.canExtract(it) }

    @Test
    fun testQualifiedCallIdIsExtracted() {
        assertEquals(
            mapOf("\"greeting.hello\"" to true),
            extractionMap("const f = (intl) => intl.formatMessage({ id: 'greeting.hello' });".replace('\'', '"'))
        )
    }

    @Test
    fun testBareCallIdIsExtracted() {
        assertEquals(
            mapOf("\"greeting.hello\"" to true),
            extractionMap("const f = () => formatMessage({ id: \"greeting.hello\" });")
        )
    }

    @Test
    fun testDefaultMessageIsNotExtracted() {
        assertEquals(
            mapOf("\"greeting.hello\"" to true, "\"Hello there\"" to false),
            extractionMap("const f = (intl) => intl.formatMessage({ id: \"greeting.hello\", defaultMessage: \"Hello there\" });")
        )
    }

    @Test
    fun testOtherCallIsNotExtracted() {
        assertEquals(
            mapOf("\"greeting.hello\"" to false),
            extractionMap("const f = (s) => s({ id: \"greeting.hello\" });")
        )
    }
}
