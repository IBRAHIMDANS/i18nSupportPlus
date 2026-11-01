package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A string shown to the user outside JSX — a toast, an alert, a title property — is reported, and
 * the other strings of a program are not.
 */
class HardcodedJsStringInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"

    private fun reportedIn(code: String, fileName: String = "notify.ts", inspection: HardcodedJsStringInspection = HardcodedJsStringInspection()): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(inspection)
            myFixture.configureByText(fileName, code)
            reported = myFixture.doHighlighting()
                .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
                .map { it.text }
        }
        return reported
    }

    @Test
    fun argumentsOfTheConfiguredFunctionsAreReported() {
        val reported = reportedIn(
            """
            declare const toast: any;
            alert('Done');
            toast('Saved');
            toast.error('Could not save');
            confirm("Delete the file?");
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("'Done'", "'Saved'", "'Could not save'", "\"Delete the file?\""), reported)
    }

    @Test
    fun valuesOfTheConfiguredPropertiesAreReported() {
        Assertions.assertEquals(
            listOf("'Settings'", "'Your name'"),
            reportedIn("export const page = { id: 'settings', title: 'Settings', field: { placeholder: 'Your name' } };")
        )
    }

    @Test
    fun otherStringsAreIgnored() {
        val reported = reportedIn(
            """
            declare const toast: any, t: any, i18n: any;
            console.log('Loading the page');
            const route = { path: 'settings', component: 'Page' };
            toast(t('saved'));
            toast(i18n.t('saved'));
            toast('—');
            toast(`Saved ${'$'}{1}`);
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun theListsAreOptions() {
        val inspection = HardcodedJsStringInspection().apply { functions = "notify"; properties = "caption" }
        val reported = reportedIn("declare const notify: any, toast: any;\nnotify('Hi');\ntoast('Saved');\nconst a = { caption: 'Profile', title: 'X' };", inspection = inspection)
        Assertions.assertEquals(listOf("'Hi'", "'Profile'"), reported)
    }

    @Test
    fun aVueComponentIsLeftToItsOwnInspection() {
        Assertions.assertEquals(emptyList<String>(), reportedIn("<script>\nalert('Done');\n</script>", "Page.vue"))
    }

    @Test
    fun namesAreSplitOnCommasAndBlanks() {
        Assertions.assertEquals(setOf("alert", "toast", "notify"), HardcodedJsStringInspection.names(" alert, toast\nnotify ,"))
    }

    @Test
    fun theQuickFixExtractsTheString() {
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedJsStringInspection::class.java)
            myFixture.configureByText("notify.js", "toast('Sa<caret>ved');")
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog("test:ref.saved"))
            myFixture.launchActionAndWait(fix)
            val result = myFixture.editor.document.text
            Assertions.assertTrue("'test:ref.saved'" in result && "'Saved'" !in result, result)
        }
    }
}
