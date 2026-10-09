package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.injection.InjectedLanguageManager
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Laravel's helpers inside Blade echoes: `{{ __('key') }}`, `{!! trans('key') !!}`. The Blade
 * plugin injects PHP there, which the PHP annotator and references serve like any PHP file.
 */
class BladeEchoTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("composer.json", """{"require": {"laravel/framework": "^11.0"}}""")
        myFixture.addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""")
    }

    private fun unresolvedIn(path: String, code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        Assertions.assertEquals("Blade", myFixture.file.language.id, "the view must be parsed as Blade")
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun anUnknownKeyIsReportedInEveryEcho() {
        val reported = unresolvedIn(
            "resources/views/a.blade.php",
            """
            <p>{{ __('test:ref.key') }}</p>
            <p>{{ __('test:ref.gone') }}</p>
            <p>{!! trans('test:ref.lost') !!}</p>
            <p>{{ trans_choice('test:ref.missing', 2) }}</p>
            """.trimIndent()
        )
        Assertions.assertEquals(3, reported.size, "reported: $reported")
    }

    @Test
    fun aKeyInAnEchoResolvesToItsTranslation() {
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("resources/views/b.blade.php", "<p>{{ __('test:ref.k<caret>ey') }}</p>").virtualFile
        )
        val host = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, myFixture.caretOffset)
        val element = (injected ?: host).parent
        Assertions.assertEquals("Value", element.references.firstOrNull()?.resolve()?.text?.unQuote())
    }
}
