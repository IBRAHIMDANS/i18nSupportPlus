package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.javascript.JavascriptLanguage
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hovering Paraglide's `m.hello` lists the message in every locale, variants as one branch. */
class ParaglideHoverTest : PlatformBaseTest() {

    private fun hover(code: String): String? {
        addFileToProject("messages/en.json", """{"hello": "Hello", "cart": [{"selectors": ["n"], "match": {"n=one": "One", "n=other": "{n} items"}}]}""")
        addFileToProject("messages/de.json", """{"hello": "Hallo"}""")
        myFixture.configureByText("A.ts", "import { m } from './paraglide/messages.js';\n$code")
        var doc: String? = null
        read { doc = HintProvider().generateDoc(null, myFixture.file.findElementAt(myFixture.caretOffset)) }
        return doc
    }

    @Test
    fun aMessageIsShownInEveryLocale() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        val doc = hover("const a = m.hel<caret>lo();")
        assertNotNull(doc)
        assertTrue(doc!!.contains("Hello") && doc.contains("Hallo"), doc)
        assertTrue(doc.indexOf("<b>en</b>") < doc.indexOf("<b>de</b>"), "the preview locale leads: $doc")
    }

    @Test
    fun variantsShowTheirCatchAllBranch() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        val doc = hover("const a = m.ca<caret>rt({ n: 2 });")
        assertTrue(doc!!.contains("{n} items…") && !doc.contains("selectors"), doc)
    }

    /** Ours answers before the JavaScript documentation of the generated function. */
    @Test
    fun theProviderComesFirstForJavaScript() {
        val providers = LanguageDocumentation.INSTANCE.allForLanguage(JavascriptLanguage)
        val ours = providers.indexOfFirst { it is HintProvider }
        val javaScript = providers.indexOfFirst { it.javaClass.simpleName == "JavaScriptDocumentationProvider" }
        val names = providers.map { it.javaClass.name }.toString()
        assertTrue(ours >= 0 && (javaScript < 0 || ours < javaScript), names)
    }
}
