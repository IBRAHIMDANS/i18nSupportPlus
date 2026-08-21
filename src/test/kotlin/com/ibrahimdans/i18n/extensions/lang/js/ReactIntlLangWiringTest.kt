package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that react-intl keys travel the whole Lang pipeline: canExtractKey → extractRawKey →
 * RawKeyParser. The annotator only adds the resolution step on top of this.
 */
class ReactIntlLangWiringTest : PlatformBaseTest() {

    private val fnNames = listOf("t", "i18n.t", "formatMessage")

    @Test
    fun testFormatMessageDescriptorTravelsThePipeline() {
        myFixture.configureByText(
            "test.tsx",
            "const f = (intl) => intl.formatMessage({ id: \"greeting.hello\" });"
        )
        val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java)
            .first { it.text == "\"greeting.hello\"" }

        val lang = JsxLang()
        assertTrue(lang.canExtractKey(literal, fnNames), "canExtractKey")
        val rawKey = lang.extractRawKey(literal)
        assertNotNull(rawKey, "extractRawKey")
        val fullKey = RawKeyParser(myFixture.project).parse(rawKey!!)
        assertNotNull(fullKey, "RawKeyParser")
        assertEquals("greeting.hello", fullKey!!.source)
    }

    @Test
    fun testFormattedMessageAttributeTravelsThePipeline() {
        myFixture.configureByText(
            "test.tsx",
            "const C = () => (<FormattedMessage id=\"greeting.hello\" />);"
        )
        val attributeValue = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlAttributeValue::class.java)
            .first { it.value == "greeting.hello" }

        val lang = JsxLang()
        assertTrue(lang.canExtractKey(attributeValue, fnNames), "canExtractKey")
        val rawKey = lang.extractRawKey(attributeValue)
        assertNotNull(rawKey, "extractRawKey")
        val fullKey = RawKeyParser(myFixture.project).parse(rawKey!!)
        assertNotNull(fullKey, "RawKeyParser")
        assertEquals("greeting.hello", fullKey!!.source)
    }
}
