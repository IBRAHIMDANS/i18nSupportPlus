package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in the HTML of a Blade view is reported — never an echo, a directive nor a text
 * one of them splits — and its quick fix writes Laravel's `{{ __('key') }}`.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class HardcodedBladeTextInspectionTest : ExtractionTestBase() {

    private val message = "Hard-coded text: extract it to a translation key"

    private fun reportedIn(view: String): List<String> {
        var reported = emptyList<String>()
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedBladeTextInspection::class.java)
            myFixture.configureByText("welcome.blade.php", view)
            Assertions.assertEquals("Blade", myFixture.file.fileType.name, "the view must be parsed as Blade")
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
    }

    @Test
    fun translatedTextEchoesDirectivesAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{{ __('messages.save') }}</p>
            <p>@lang('messages.save')</p>
            <p>Hello {{ ${'$'}user->name }}!</p>
            <input title="{{ __('messages.name') }}" placeholder="@lang('messages.name')">
            @if(${'$'}admin)<b>{{ trans('messages.admin') }}</b>@endif
            <code>php artisan migrate</code>
            <script>const label = 'Save';</script>
            <p>42 —</p>
            """.trimIndent()
        )
        Assertions.assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun theQuickFixWritesTheHelper() {
        extract("<div>\n<button>Sa<caret>ve changes</button>\n</div>", "test:ref.save")
        myFixture.checkResult("<div>\n<button>{{ __('test:ref.save') }}</button>\n</div>")
    }

    @Test
    fun theQuickFixEchoesAnAttribute() {
        extract("""<input placeholder="Your na<caret>me">""", "test:ref.name")
        myFixture.checkResult("""<input placeholder="{{ __('test:ref.name') }}">""")
    }

    private fun extract(view: String, key: String) {
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedBladeTextInspection::class.java)
            myFixture.configureByText("welcome.blade.php", view)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
        }
    }
}
