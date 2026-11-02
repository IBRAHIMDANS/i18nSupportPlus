package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in the template of an Astro component is reported — never its frontmatter nor a
 * call of `t` — and its quick fix writes `{t('key')}`, importing `t` when the component has none.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class HardcodedAstroTextInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"
    private val frontmatter = "---\nimport { t } from 'i18next';\nconst user = { name: 'x' };\n---\n"

    private fun reportedIn(template: String): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedAstroTextInspection::class.java)
            myFixture.configureByText("Page.astro", frontmatter + template)
            Assertions.assertEquals("Astro", myFixture.file.fileType.name, "the component must be parsed as Astro")
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
    fun translatedTextFrontmatterAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{t('save')}</p>
            <input title={t('name')}>
            <p>Total {count()}</p>
            <code>npm install</code>
            <style>p { color: red; }</style>
            <p>42 —</p>
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun theQuickFixWritesT() {
        extract(frontmatter + "<button>Sa<caret>ve changes</button>", "test:ref.save")
        myFixture.checkResult(frontmatter + "<button>{t('test:ref.save')}</button>")
    }

    @Test
    fun theQuickFixPassesTheVariables() {
        val written = extract(frontmatter + "<p>Hel<caret>lo {user.name}!</p>", "test:ref.greeting")
        myFixture.checkResult(frontmatter + "<p>{t('test:ref.greeting', { name: user.name })}</p>")
        Assertions.assertTrue(written.contains("\"Hello {{name}}!\""), written)
    }

    @Test
    fun theQuickFixBindsAnAttribute() {
        extract(frontmatter + """<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult(frontmatter + """<input placeholder={t('test:ref.name')}>""")
    }

    @Test
    fun theQuickFixImportsTWhenTheComponentHasNone() {
        extract("---\nconst title = 'x';\n---\n<button>Sa<caret>ve</button>", "test:ref.save")
        myFixture.checkResult("---\nimport { t } from 'i18next';\nconst title = 'x';\n---\n<button>{t('test:ref.save')}</button>")
    }

    @Test
    fun theQuickFixCreatesTheFrontmatterWhenTheComponentHasNone() {
        extract("<button>Sa<caret>ve</button>", "test:ref.save")
        myFixture.checkResult("---\nimport { t } from 'i18next';\n---\n<button>{t('test:ref.save')}</button>")
    }

    @Test
    fun theFunctionFollowsTheComponentsImport() {
        Assertions.assertEquals("tr", AstroMarkupTextExtractor.functionOf("import { t as tr } from 'i18next';"))
        Assertions.assertEquals("t", AstroMarkupTextExtractor.functionOf("import { t, changeLanguage } from \"astro-i18next\";"))
        Assertions.assertEquals("t", AstroMarkupTextExtractor.functionOf("import { useTranslations } from '../i18n/utils';"))
    }

    /** Without a preset, a component importing Paraglide's message object gets its calls and its `{name}` placeholders. */
    @Test
    fun aComponentImportingParaglideGetsMessageCalls() {
        val written = extract(paraglideImport + "<p>Hel<caret>lo {user.name}!</p>", "test:ref.greeting")
        myFixture.checkResult(paraglideImport + "<p>{m[\"test:ref.greeting\"]({ name: user.name })}</p>")
        Assertions.assertTrue(written.contains("\"Hello {name}!\""), written)
    }

    @Test
    fun aComponentImportingParaglideBindsAnAttributeToAMessageCall() {
        extract(paraglideImport + """<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult(paraglideImport + """<input placeholder={m["test:ref.name"]()}>""")
    }

    private val paraglideImport = "---\nimport { m } from '../paraglide/messages.js';\nconst user = { name: 'x' };\n---\n"

    /** Under the `paraglide` preset, the message object is imported in the frontmatter when the component lacks it. */
    @Test
    fun theParaglidePresetImportsTheMessageObject() {
        extract("---\nconst title = 'x';\n---\n<button>Sa<caret>ve</button>", "save", preset = "paraglide")
        myFixture.checkResult("---\nimport { m } from './paraglide/messages.js';\nconst title = 'x';\n---\n<button>{m.save()}</button>")
    }

    /** A preset naming a library Astro has no binding for falls back to i18next's `t` and `{{name}}`. */
    @Test
    fun aPresetWithoutAstroBindingKeepsI18next() {
        val written = extract(frontmatter + "<p>Hel<caret>lo {user.name}!</p>", "test:ref.greeting", preset = "vue-i18n")
        myFixture.checkResult(frontmatter + "<p>{t('test:ref.greeting', { name: user.name })}</p>")
        Assertions.assertTrue(written.contains("\"Hello {{name}}!\""), written)
    }

    private fun extract(component: String, key: String, preset: String? = null): String {
        var written = ""
        val modules = listOfNotNull(preset?.let { ModuleConfig(name = "app", rootDirectory = "src", preset = it) })
        myFixture.runWithConfig(config("json").copy(modules = modules)) {
            myFixture.enableInspections(HardcodedAstroTextInspection::class.java)
            myFixture.configureByText("Page.astro", component)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
            written = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        return written
    }
}
