package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hardcoded text in a Vue template is reported — and nothing else is: an inspection that
 * underlines code gets disabled at once.
 */
class HardcodedVueTextInspectionTest : PlatformBaseTest() {

    private val message = "Hardcoded text: move it to a translation key"

    /** The texts this inspection reports in a component whose template holds [template]. */
    private fun reportedIn(template: String, script: String = ""): List<String> {
        myFixture.enableInspections(HardcodedVueTextInspection::class.java)
        myFixture.configureByText("App.vue", "<template>\n$template\n</template>\n$script")
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WEAK_WARNING && it.description == message }
            .map { it.text }
    }

    @Test
    fun textInATagIsReported() {
        assertEquals(listOf("Save changes"), reportedIn("<div><button>  Save changes </button></div>"))
    }

    @Test
    fun textAroundAnInterpolationIsReported() {
        assertEquals(listOf("Hello {{ name }}!"), reportedIn("<p>Hello {{ name }}!</p>"))
    }

    @Test
    fun anInterpolationAloneIsNotText() {
        assertTrue(reportedIn("<p>{{ \$t('greeting') }}</p><p>{{ user.name }}</p>").isEmpty())
    }

    @Test
    fun eachTextOfANestedTagIsReported() {
        assertEquals(listOf("Hi", "you"), reportedIn("<p>Hi <b>you</b></p>"))
    }

    @Test
    fun visibleAttributesAreReported() {
        val reported = reportedIn(
            """<div><input placeholder="Your name"><img alt="Logo"><a title="Home"></a><i aria-label="Close"></i></div>"""
        )
        assertEquals(listOf("Your name", "Logo", "Home", "Close"), reported)
    }

    @Test
    fun textualAriaAttributesAndLabelAreReported() {
        val reported = reportedIn(
            """<div><i aria-description="Opens the menu" aria-roledescription="slide"></i><div aria-placeholder="Search"></div><select><option value="fr" label="French"></option></select><input aria-labelledby="name-label" aria-describedby="name-help"></div>"""
        )
        assertEquals(listOf("Opens the menu", "slide", "Search", "French"), reported)
    }

    @Test
    fun boundAndTechnicalAttributesAreIgnored() {
        val reported = reportedIn(
            """<div><a :title="label" v-bind:alt="alt" class="title text" style="color: red" id="main" data-testid="save button" type="submit" href="/home"></a></div>"""
        )
        assertTrue(reported.isEmpty(), "$reported")
    }

    @Test
    fun punctuationNumbersAndEntitiesAreIgnored() {
        assertTrue(reportedIn("<p>— : 42 % &nbsp; &copy; 3.14</p>").isEmpty())
    }

    @Test
    fun codeAndVueI18nComponentsAreIgnored() {
        val reported = reportedIn(
            """<div><code>npm install</code><pre>yarn add</pre><i18n-t keypath="terms"><a>terms</a></i18n-t></div>"""
        )
        assertTrue(reported.isEmpty(), "$reported")
    }

    @Test
    fun theScriptAndStyleAreIgnored() {
        val reported = reportedIn(
            "<div></div>",
            "<script>\nexport default { data: () => ({ label: 'Save' }) }\n</script>\n<style>\n.a { content: 'Hello'; }\n</style>"
        )
        assertTrue(reported.isEmpty(), "$reported")
    }

    @Test
    fun aJsxFileIsLeftToTheJsxInspection() {
        myFixture.enableInspections(HardcodedVueTextInspection::class.java)
        myFixture.configureByText("App.tsx", "export const App = () => <button>Save</button>;")
        val reported = myFixture.doHighlighting().filter { it.description == message }
        assertTrue(reported.isEmpty(), "$reported")
    }
}
