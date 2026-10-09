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

    // ── Which files hold JSX ──────────────────────────────────────────────────

    /** The text of the first `<p>` of [code], written in a file named [name]. */
    private fun paragraphText(name: String, code: String): XmlText {
        val file = myFixture.configureByText(name, code)
        val tag = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).first { it.name == "p" }
        return PsiTreeUtil.findChildOfType(tag, XmlText::class.java)!!
    }

    /** CRA and Vite write JSX in `.js` files: the extension alone used to rule them out. */
    @Test
    fun testCanExtract_jsxInAJsFile() {
        val text = paragraphText("App.js", "export const App = () => <p>Save</p>;")
        Assertions.assertTrue(extractor.canExtract(text.firstChild))
    }

    @Test
    fun testCanExtract_jsxAndTsxFilesUnchanged() {
        Assertions.assertTrue(extractor.canExtract(paragraphText("App.jsx", "export const App = () => <p>Save</p>;").firstChild))
        Assertions.assertTrue(extractor.canExtract(paragraphText("App.tsx", "export const App = () => <p>Save</p>;").firstChild))
    }

    /** A `.js` file without JSX holds no tag: its strings stay with [JsTranslationExtractor]. */
    @Test
    fun testCanExtract_aJsFileWithoutJsxIsLeftToTheJsExtractor() {
        val file = myFixture.configureByText("labels.js", "export const label = 'Save';")
        val literal = file.findElementAt(file.text.indexOf("Save"))!!
        Assertions.assertFalse(extractor.canExtract(literal))
        Assertions.assertTrue(JsTranslationExtractor().canExtract(literal))
    }

    /** The children of a tag split its text, not its attributes. */
    @Test
    fun testCanExtract_anAttributeOfATagHoldingAnotherTag() {
        val file = myFixture.configureByText("App.tsx", "export const App = () => <a title=\"Home\">Go <b>home</b></a>;")
        val title = file.findElementAt(file.text.indexOf("Home"))!!
        val go = file.findElementAt(file.text.indexOf("Go"))!!
        Assertions.assertTrue(extractor.canExtract(title))
        Assertions.assertFalse(extractor.canExtract(go))
    }

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

    // ── The function the call uses ────────────────────────────────────────────

    private fun templateOf(code: String): String {
        val text = paragraphText("App.tsx", code)
        return extractor.template(text.firstChild)("'k'")
    }

    /** A component holding `t` from `useTranslation` gets `t(…)`: `i18n` is not imported there. */
    @Test
    fun testTemplate_usesTheTOfUseTranslation() {
        val code = "export const App = () => { const { t } = useTranslation('account'); return <p>Save</p>; };"
        Assertions.assertEquals("{t('k')}", templateOf(code))
        Assertions.assertEquals(listOf("account"), extractor.scopeNamespaces(paragraphText("App.tsx", code).firstChild))
    }

    @Test
    fun testTemplate_aHookOfAnEnclosingFunctionCounts() {
        val code = "export const App = () => { const { t } = useTranslation(['account', 'common']); const row = () => <p>Save</p>; return row(); };"
        Assertions.assertEquals("{t('k')}", templateOf(code))
        Assertions.assertEquals(listOf("account", "common"), extractor.scopeNamespaces(paragraphText("App.tsx", code).firstChild))
    }

    @Test
    fun testTemplate_withoutHookKeepsI18nT() {
        Assertions.assertEquals("{i18n.t('k')}", templateOf("export const App = () => <p>Save</p>;"))
    }

    /** A `keyPrefix` makes `t` read relative keys; the extracted key is a whole one. */
    @Test
    fun testTemplate_aKeyPrefixKeepsI18nT() {
        val code = "export const App = () => { const { t } = useTranslation('account', { keyPrefix: 'menu' }); return <p>Save</p>; };"
        Assertions.assertEquals("{i18n.t('k')}", templateOf(code))
    }

    /** A hook in a sibling component is out of scope. */
    @Test
    fun testTemplate_aHookOfAnotherComponentIsIgnored() {
        val code = "const A = () => { const { t } = useTranslation(); return null; };\nexport const B = () => <p>Save</p>;"
        Assertions.assertEquals("{i18n.t('k')}", templateOf(code))
    }
}
