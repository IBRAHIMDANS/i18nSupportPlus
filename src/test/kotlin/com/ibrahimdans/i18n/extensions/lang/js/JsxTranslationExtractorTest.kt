package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

class JsxTranslationExtractorTest : PlatformBaseTest() {

    private val extractor = JsxTranslationExtractor()

    // ── Null parent XmlTag ────────────────────────────────────────────────────

    @Test
    fun testText_noParentXmlTag_fallsBackToElementText() {
        myFixture.configureByText("Test.tsx", "const x = 1")
        assertDoesNotThrow { extractor.text(myFixture.file) }
    }

    @Test
    fun testTextRange_noParentXmlTag_fallsBackToElementTextRange() {
        myFixture.configureByText("Test.tsx", "const x = 1")
        assertDoesNotThrow { extractor.textRange(myFixture.file) }
    }

    // ── Empty JSX tag (<Inner></Inner>) — textElements would be empty ─────────

    @Test
    fun testTextRange_emptyNestedTag_doesNotThrow() {
        // <Outer> has only a child tag, no text nodes — textElements is empty.
        // Before the fix, this would throw NoSuchElementException on first()/last().
        myFixture.configureByText(
            "Test.tsx",
            "export default function App() { return <Outer><Inner></Inner></Outer>; }"
        )
        val outerTag = PsiTreeUtil.findChildOfType(myFixture.file, XmlTag::class.java)
        val innerTag = outerTag?.let { PsiTreeUtil.findChildOfType(it, XmlTag::class.java) }
        Assumptions.assumeTrue(innerTag != null) // JSX not parsed as XmlTag in this platform version — skip
        assertDoesNotThrow { extractor.textRange(innerTag!!) }
    }

    @Test
    fun testText_emptyNestedTag_doesNotThrow() {
        myFixture.configureByText(
            "Test.tsx",
            "export default function App() { return <Outer><Inner></Inner></Outer>; }"
        )
        val outerTag = PsiTreeUtil.findChildOfType(myFixture.file, XmlTag::class.java)
        val innerTag = outerTag?.let { PsiTreeUtil.findChildOfType(it, XmlTag::class.java) }
        Assumptions.assumeTrue(innerTag != null) // JSX not parsed as XmlTag in this platform version — skip
        assertDoesNotThrow { extractor.text(innerTag!!) }
    }
}
