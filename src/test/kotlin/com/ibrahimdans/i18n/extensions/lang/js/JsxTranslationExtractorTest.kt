package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import org.junit.jupiter.api.Assertions
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

    // ── Placeholder syntax of the module's technology ─────────────────────────

    /** The message extracted from `<p>Hello {name}, {count} new</p>` in a module of [preset]. */
    private fun messageUnder(preset: String?): String {
        val modules = preset?.let { listOf(ModuleConfig(name = "app", rootDirectory = "src", preset = it)) }.orEmpty()
        var message = ""
        myFixture.runWithConfig(Config(modules = modules)) {
            myFixture.configureByText(
                "App.tsx",
                "export const App = ({ name, count }: any) => <p>Hello {name}, {count} new</p>;"
            )
            val text = PsiTreeUtil.findChildOfType(myFixture.file, XmlText::class.java)
            Assumptions.assumeTrue(text != null) // JSX not parsed as XmlTag in this platform version — skip
            message = extractor.text(text!!.firstChild)
        }
        return message
    }

    @Test
    fun testText_withoutPreset_writesI18nextPlaceholders() {
        Assertions.assertEquals("Hello {{name}}, {{count}} new", messageUnder(null))
        Assertions.assertEquals("Hello {{name}}, {{count}} new", messageUnder("i18next"))
    }

    @Test
    fun testText_singleBraceTechnology_writesSingleBraces() {
        listOf("lingui", "react-intl", "vue-i18n", "svelte-i18n").forEach { preset ->
            Assertions.assertEquals("Hello {name}, {count} new", messageUnder(preset), preset)
        }
    }

    @Test
    fun testText_i18nJs_writesPercentBraces() {
        Assertions.assertEquals("Hello %{name}, %{count} new", messageUnder("i18n-js"))
    }
}
