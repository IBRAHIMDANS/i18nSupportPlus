package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.injection.InjectedLanguageManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Blade's `@lang('key')` and `@choice('key', n)`, which the Blade plugin injects as
 * `app('translator')->get('key')` and `->choice('key', n)`, and those calls written in PHP.
 */
class BladeDirectivesTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""")
    }

    private fun laravelProject() {
        myFixture.addFileToProject("composer.json", """{"require": {"laravel/framework": "^11.0"}}""")
    }

    /** The keys reported unresolved in the file at [path], as written. */
    private fun unresolvedIn(path: String, code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun unknownKeysOfTheDirectivesAreReported() {
        laravelProject()
        val reported = unresolvedIn(
            "resources/views/a.blade.php",
            """
            <p>@lang('test:ref.key')</p>
            <p>@lang('test:ref.gone')</p>
            <p>@choice('test:ref.lost', 2)</p>
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("gone", "lost"), reported.map { it.substringAfterLast('.') }, "reported: $reported")
    }

    @Test
    fun aDirectiveKeyResolvesToItsTranslation() {
        laravelProject()
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("resources/views/b.blade.php", "<p>@lang('test:ref.k<caret>ey')</p>").virtualFile
        )
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, myFixture.caretOffset)
        val element = (injected ?: myFixture.file.findElementAt(myFixture.caretOffset)!!).parent
        Assertions.assertEquals("Value", element.references.firstOrNull()?.resolve()?.text?.unQuote())
    }

    @Test
    fun withoutLaravelTheDirectivesAreLeftAlone() {
        Assertions.assertEquals(emptyList<String>(), unresolvedIn("resources/views/c.blade.php", "<p>@lang('test:ref.gone')</p>"))
    }

    /** `@include('view.name')` is injected as `$__env->make('view.name', …)`: a view, never a key. */
    @Test
    fun anIncludeIsNoKey() {
        laravelProject()
        Assertions.assertEquals(emptyList<String>(), unresolvedIn("resources/views/d.blade.php", "@include('test:ref.gone')"))
    }

    @Test
    fun theTranslatorCalledInPhpIsRecognisedAndAnotherGetIsNot() {
        laravelProject()
        val reported = unresolvedIn(
            "app/Http/Controller.php",
            """
            <?php
            ${'$'}a = app('translator')->get('test:ref.gone');
            ${'$'}b = \Illuminate\Support\Facades\Lang::choice('test:ref.lost', 2);
            ${'$'}c = ${'$'}request->get('test:ref.missing');
            ${'$'}d = app('cache')->get('test:ref.missing');
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("gone", "lost"), reported.map { it.substringAfterLast('.') }, "reported: $reported")
    }
}
