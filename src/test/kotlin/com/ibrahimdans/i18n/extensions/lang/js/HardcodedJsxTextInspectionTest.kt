package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hardcoded JSX text is reported — and nothing else is: an inspection that underlines code gets
 * disabled at once.
 */
class HardcodedJsxTextInspectionTest : ExtractionTestBase() {

    private val message = "Hardcoded text: extract it to a translation key"

    /** The texts this inspection reports in a component returning [jsx], in a file of [extension]. */
    private fun reportedIn(jsx: String, extension: String = "tsx"): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedJsxTextInspection::class.java)
            myFixture.configureByText(
                "App.$extension",
                """
                export default function App({ name, user }: any) {
                    return $jsx;
                }
                """.trimIndent()
            )
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text.trim() }
        }
        return reported
    }

    @Test
    fun textInATagIsReported() {
        assertEquals(listOf("Save"), reportedIn("<button>Save</button>"))
        assertEquals(listOf("Save changes"), reportedIn("<button>Save changes</button>", "jsx"))
    }

    @Test
    fun visibleAttributesAreReported() {
        val reported = reportedIn(
            """<div><input placeholder="Your name"/><img alt="Logo"/><a title="Home"/><i aria-label="Close"/></div>"""
        )
        assertEquals(listOf("\"Your name\"", "\"Logo\"", "\"Home\"", "\"Close\""), reported)
    }

    @Test
    fun technicalAttributesAreIgnored() {
        val reported = reportedIn(
            """<div><span className="title text" style="color" key="row" id="main" data-testid="save button" type="submit" href="/home"/></div>"""
        )
        assertTrue(reported.isEmpty(), "$reported")
    }

    @Test
    fun punctuationNumbersAndWhitespaceAreIgnored() {
        listOf("<span>—</span>", "<span>42</span>", "<span>3.14 %</span>", "<span>&nbsp;</span>", "<span>(…)</span>", "<span> </span>")
            .forEach { assertTrue(reportedIn(it).isEmpty(), it) }
    }

    @Test
    fun textAlreadyTranslatedOrNotExtractableIsIgnored() {
        listOf(
            "<span>{t('save')}</span>",
            "<a title={t('home')}/>",
            "<a title={'Home'}/>",
            "<Trans i18nKey=\"welcome\">Welcome back</Trans>",
            "<code>npm install</code>",
            // The extraction would take `Hello` alone, or the expression along with the text.
            "<p>Hello {name}</p>",
            "<p>{name}, welcome</p>",
        ).forEach { assertTrue(reportedIn(it).isEmpty(), it) }
    }

    /** `Hello` sits next to a tag, which the extraction does not handle; `you` is a text of its own. */
    @Test
    fun onlyTheTextOfATagWithoutChildTagIsReported() {
        assertEquals(listOf("you"), reportedIn("<p>Hello <b>you</b></p>"))
    }

    @Test
    fun plainJavaScriptFilesAreIgnored() {
        assertTrue(reportedIn("'Save'", "ts").isEmpty())
    }

    @Test
    fun theQuickFixExtractsTheText() {
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedJsxTextInspection::class.java)
            myFixture.configureByText("App.jsx", "export const App = (i18n) => (<button>Sa<caret>ve changes</button>);")
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog("test:ref.save"))
            myFixture.launchActionAndWait(fix)
            myFixture.checkResult("export const App = (i18n) => (<button>{i18n.t('test:ref.save')}</button>);")
            val translations = myFixture.findFileInTempDir("assets/test.json")
            val written = FileDocumentManager.getInstance().getDocument(translations)!!.text
            assertTrue(written.contains("\"save\"") && written.contains("Save changes"), written)
            assertFalse(myFixture.doHighlighting().any { it.description == message })
        }
    }
}
