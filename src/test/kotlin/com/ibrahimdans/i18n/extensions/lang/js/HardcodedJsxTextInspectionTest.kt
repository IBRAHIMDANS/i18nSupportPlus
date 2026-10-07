package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
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
    private fun reportedIn(jsx: String, extension: String = "tsx", config: Config = config("json")): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config) {
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
    fun textualAriaAttributesAndLabelAreReported() {
        val reported = reportedIn(
            """<div><i aria-description="Opens the menu" aria-roledescription="slide"/><div aria-placeholder="Search"/><option value="fr" label="French"/><Field name="email" label="Email"/></div>"""
        )
        assertEquals(listOf("\"Opens the menu\"", "\"slide\"", "\"Search\"", "\"French\"", "\"Email\""), reported)
    }

    @Test
    fun ariaAttributesNamingAnIdAreIgnored() {
        val reported = reportedIn("""<div><input aria-labelledby="name-label" aria-describedby="name-help"/></div>""")
        assertTrue(reported.isEmpty(), "$reported")
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
        ).forEach { assertTrue(reportedIn(it).isEmpty(), it) }
    }

    @Test
    fun textAroundAVariableIsReportedOnceOverTheWholeMessage() {
        assertEquals(listOf("Hello {name}"), reportedIn("<p>Hello {name}</p>"))
        assertEquals(listOf("{name}, welcome"), reportedIn("<p>{name}, welcome</p>"))
        assertEquals(listOf("Hello {user.name}, you have {count} items"), reportedIn("<p>Hello {user.name}, you have {count} items</p>"))
    }

    /** A call, a condition or a comment cannot become a variable: the extraction would break it. */
    @Test
    fun textAroundAnExpressionThatIsNotAVariableIsIgnored() {
        listOf(
            "<p>Hello {user.getName()}</p>",
            "<p>Hello {name ? name : 'you'}</p>",
            "<p>Hello {/* who */}</p>",
            // Both would be `{{name}}`.
            "<p>{user.name} meets {name}</p>",
            "<p>{name}</p>",
        ).forEach { assertTrue(reportedIn(it).isEmpty(), it) }
    }

    /** In a single-brace technology the extraction writes `{name}`: a variable alone is still no text. */
    @Test
    fun aVariableAloneIsIgnoredWhateverThePlaceholderSyntax() {
        listOf("lingui", "i18n-js").forEach { preset ->
            val config = config("json").copy(modules = listOf(ModuleConfig(name = "app", rootDirectory = "src", preset = preset)))
            assertTrue(reportedIn("<p>{name}</p>", config = config).isEmpty(), preset)
            assertEquals(listOf("Hello {name}"), reportedIn("<p>Hello {name}</p>", config = config), preset)
        }
    }

    /** `Hello` sits next to a tag, which the extraction does not handle; `you` is a text of its own. */
    @Test
    fun onlyTheTextOfATagWithoutChildTagIsReported() {
        assertEquals(listOf("you"), reportedIn("<p>Hello <b>you</b></p>"))
    }

    /** CRA and Vite projects write JSX in `.js` files. */
    @Test
    fun jsxInAJsFileIsReported() {
        assertEquals(listOf("Save"), reportedIn("<button>Save</button>", "js"))
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

    @Test
    fun theQuickFixPassesTheVariablesOfTheText() {
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedJsxTextInspection::class.java)
            myFixture.configureByText("App.jsx", "export const App = (i18n, user, count) => (<p>Hel<caret>lo {user.name}, {count} new</p>);")
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog("test:ref.greeting"))
            myFixture.launchActionAndWait(fix)
            myFixture.checkResult(
                "export const App = (i18n, user, count) => (<p>{i18n.t('test:ref.greeting', { name: user.name, count })}</p>);"
            )
            val translations = myFixture.findFileInTempDir("assets/test.json")
            val written = FileDocumentManager.getInstance().getDocument(translations)!!.text
            assertTrue(written.contains("\"Hello {{name}}, {{count}} new\""), written)
        }
    }
}
