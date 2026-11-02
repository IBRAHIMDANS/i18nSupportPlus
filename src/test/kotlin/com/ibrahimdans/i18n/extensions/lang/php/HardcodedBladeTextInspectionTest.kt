package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionTestBase
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager.setTestInputDialog
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Hard-coded text in the HTML of a Blade view is reported — an echo of a plain value as one of its
 * variables, never a raw echo, a directive nor a text one of them splits — and its quick fix writes
 * Laravel's `{{ __('key') }}`, with `['name' => $value]` and a `:name` message for an echo.
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
    fun aTextAnEchoOfAValueSplitsIsReportedOnce() {
        Assertions.assertEquals(listOf("Hello {{ ${'$'}user->name }}!"), reportedIn("<p>Hello {{ ${'$'}user->name }}!</p>"))
        Assertions.assertEquals(listOf("{{ ${'$'}count }} items in {{ ${'$'}cart }}"), reportedIn("<p>{{ ${'$'}count }} items in {{ ${'$'}cart }}</p>"))
    }

    @Test
    fun translatedTextEchoesDirectivesAndCodeAreIgnored() {
        val reported = reportedIn(
            """
            <p>{{ __('messages.save') }}</p>
            <p>@lang('messages.save')</p>
            <p>{{ ${'$'}user->name }}</p>
            <p>{{ ${'$'}count }} —</p>
            <p>— {{ ${'$'}name }}</p>
            <p>Hi {!! ${'$'}html !!} there</p>
            <p>Hello {{ ${'$'}user->fullName() }}!</p>
            <p>Hello {{ ${'$'}name ?? 'guest' }}!</p>
            <p>Hello {{-- the name --}} there</p>
            <p>Hello {{ ${'$'}user->name }} and {{ ${'$'}admin->name }}</p>
            <p>A @if(${'$'}x) B @endif C</p>
            <p>Hello {{ ${'$'}name }}, @lang('messages.welcome')</p>
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

    @Test
    fun theQuickFixPassesTheEchoesAsReplacements() {
        val written = extract("<p>Hel<caret>lo {{ ${'$'}user->name }}, {{ ${'$'}count }} new</p>", "test:ref.greeting")
        myFixture.checkResult("<p>{{ __('test:ref.greeting', ['name' => ${'$'}user->name, 'count' => ${'$'}count]) }}</p>")
        Assertions.assertTrue(written.contains("\"Hello :name, :count new\""), written)
    }

    private fun extract(view: String, key: String): String {
        var written = ""
        myFixture.runWithConfig(config("json")) {
            myFixture.enableInspections(HardcodedBladeTextInspection::class.java)
            myFixture.configureByText("welcome.blade.php", view)
            myFixture.addFileToProject("assets/test.json", """{"ref": {}}""")
            val fix = myFixture.getAllQuickFixes().single { it.text == hint }
            setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(fix)
            written = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("assets/test.json"))!!.text
        }
        return written
    }
}
