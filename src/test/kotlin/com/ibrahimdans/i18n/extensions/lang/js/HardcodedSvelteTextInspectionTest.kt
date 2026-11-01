package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in the markup of a Svelte component is reported — never its script nor a call of
 * svelte-i18n's store — and its quick fix writes the store, importing it when the component does not.
 */
class HardcodedSvelteTextInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"
    private val imports = "<script>\n\timport { _ } from 'svelte-i18n';\n\tlet user = { name: 'x' };\n</script>\n"

    private fun reportedIn(markup: String): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedSvelteTextInspection::class.java)
            myFixture.configureByText("App.svelte", imports + markup)
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text }
        }
        return reported
    }

    @Test
    fun textAroundAVariableAndVisibleAttributesAreReported() {
        Assertions.assertEquals(listOf("Save"), reportedIn("<button>Save</button>"))
        Assertions.assertEquals(listOf("Hello {user.name}!"), reportedIn("<p>Hello {user.name}!</p>"))
        Assertions.assertEquals(listOf("Your name"), reportedIn("""<input placeholder="Your name" title={caption}>"""))
    }

    @Test
    fun translatedTextScriptAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{${'$'}_('save')}</p>
            <p>Total {count()}</p>
            <code>npm install</code>
            <style>p { color: red; }</style>
            <p>42 —</p>
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun theQuickFixWritesTheStore() {
        extract(imports + "<button>Sa<caret>ve changes</button>", "test:ref.save")
        myFixture.checkResult(imports + "<button>{${'$'}_('test:ref.save')}</button>")
    }

    @Test
    fun theQuickFixPassesTheVariablesAsValues() {
        val written = extract(imports + "<p>Hel<caret>lo {user.name}!</p>", "test:ref.greeting")
        myFixture.checkResult(imports + "<p>{${'$'}_('test:ref.greeting', { values: { name: user.name } })}</p>")
        Assertions.assertTrue(written.contains("\"Hello {name}!\""), written)
    }

    @Test
    fun theQuickFixBindsAnAttribute() {
        extract(imports + """<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult(imports + """<input placeholder={${'$'}_('test:ref.name')}>""")
    }

    @Test
    fun theQuickFixImportsTheStoreWhenTheComponentDoesNot() {
        extract("<button>Sa<caret>ve</button>", "test:ref.save")
        myFixture.checkResult("<script>\n\timport { _ } from 'svelte-i18n';\n</script>\n\n<button>{${'$'}_('test:ref.save')}</button>")
    }

    @Test
    fun theQuickFixWritesTheStoreUnderTheNameTheComponentImportsItAs() {
        val aliased = "<script>\n\timport { _ as tr } from 'svelte-i18n';\n</script>\n"
        extract(aliased + "<button>Sa<caret>ve</button>", "test:ref.save")
        myFixture.checkResult(aliased + "<button>{${'$'}tr('test:ref.save')}</button>")
    }

    @Test
    fun theStoreFollowsTheComponentsImport() {
        Assertions.assertEquals("t", SvelteMarkupTextExtractor.storeOf("import { t, locale } from 'svelte-i18n';"))
        Assertions.assertEquals("_", SvelteMarkupTextExtractor.storeOf("import { _, t } from \"svelte-i18n\";"))
        Assertions.assertEquals("_", SvelteMarkupTextExtractor.storeOf("import { writable } from 'svelte/store';"))
        Assertions.assertEquals(setOf("_", "locale"), SvelteMarkupTextExtractor.importedNames("import { _ as tr, locale } from 'svelte-i18n'"))
        Assertions.assertEquals("tr", SvelteMarkupTextExtractor.storeOf("import { _ as tr, locale } from 'svelte-i18n'"))
        Assertions.assertEquals("translate", SvelteMarkupTextExtractor.storeOf("import { t as translate } from 'svelte-i18n'"))
    }

    private fun extract(component: String, key: String): String {
        var written = ""
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedSvelteTextInspection::class.java)
            myFixture.configureByText("App.svelte", component)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
            written = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        return written
    }
}
