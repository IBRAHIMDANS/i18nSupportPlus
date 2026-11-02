package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in the HTML of a Twig template of a Symfony project is reported — an echo of a plain
 * value as one of its variables, never a tag nor a text one splits — and its quick fix writes
 * `{{ 'key'|trans }}`, with `{'%name%': value}` and a `%name%` message for an echo.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class HardcodedTwigTextInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"

    private fun symfonyProject() {
        if (myFixture.findFileInTempDir("composer.json") != null) return
        myFixture.addFileToProject("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")
    }

    private fun reportedIn(template: String, symfony: Boolean = true): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            if (symfony) symfonyProject()
            myFixture.enableInspections(HardcodedTwigTextInspection::class.java)
            myFixture.configureByText("page.html.twig", template)
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text }
        }
        return reported
    }

    @Test
    fun textAndVisibleAttributesAreReported() {
        Assertions.assertEquals(listOf("Save changes"), reportedIn("<div>\n<button>Save changes</button>\n</div>"))
        Assertions.assertEquals(listOf("Your name"), reportedIn("""<input placeholder="Your name" name="user">"""))
        Assertions.assertEquals(listOf("Hello {{ user.name }}!"), reportedIn("<p>Hello {{ user.name }}!</p>"))
    }

    @Test
    fun translatedTextTagsAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{{ 'home.title'|trans }}</p>
            <p>{% trans %}home.title{% endtrans %}</p>
            <p>{{ user.name }}</p>
            <p>Hello {{ user.name|upper }}!</p>
            <p>A {% if x %} B {% endif %} C</p>
            <p>Hello {# a comment #} there</p>
            <input title="{{ 'home.title'|trans }}">
            <script>const label = 'Save';</script>
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun outsideASymfonyProjectNothingIsReported() {
        Assertions.assertEquals(emptyList<String>(), reportedIn("<button>Save changes</button>", symfony = false))
    }

    @Test
    fun theQuickFixWritesTheFilter() {
        extract("<div>\n<button>Sa<caret>ve changes</button>\n</div>", "test:ref.save")
        myFixture.checkResult("<div>\n<button>{{ 'test:ref.save'|trans }}</button>\n</div>")
    }

    @Test
    fun theQuickFixEchoesAnAttribute() {
        extract("""<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult("""<input placeholder="{{ 'test:ref.name'|trans }}">""")
    }

    @Test
    fun theQuickFixPassesTheEchoesAsParameters() {
        val written = extract("<p>Hel<caret>lo {{ user.name }}, {{ count }} new</p>", "test:ref.greeting")
        myFixture.checkResult("<p>{{ 'test:ref.greeting'|trans({'%name%': user.name, '%count%': count}) }}</p>")
        Assertions.assertTrue(written.contains("\"Hello %name%, %count% new\""), written)
    }

    private fun extract(template: String, key: String): String {
        var written = ""
        myFixture.runWithConfig(config("json")) {
            symfonyProject()
            myFixture.enableInspections(HardcodedTwigTextInspection::class.java)
            myFixture.configureByText("page.html.twig", template)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
            written = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        return written
    }
}
