package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.inlay.I18nInlayHintsProvider
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a Laravel key lacked next to a JSON one: Shift+F6 renamed the call sites but not the key of
 * the PHP array, `__()` and `trans()` were neither folded nor given an inlay hint, and a JSON phrase
 * holding `.` or `:` was cut at them and never resolved.
 */
class LaravelKeyActionsTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private val config = Config(foldingEnabled = true, foldingPreferredLanguage = "en", foldingMaxLength = 50)

    private fun messages(failed: String) = """
        <?php

        return [
            'welcome' => 'Welcome',
            'auth' => [
                'failed' => '$failed',
            ],
        ];
    """.trimIndent()

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun laravelProject(body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
        addFileToProject("lang/en/messages.php", messages("These credentials do not match."))
        addFileToProject("lang/fr/messages.php", messages("Identifiants incorrects."))
        addFileToProject("lang/en.json", """{"Saved.": "Changes saved.", "Hello, :name": "Hello, :name!", "Log in": "Sign in"}""")
        body()
    }

    // Each call writes a file of its own: a test may ask several times.
    private var calls = 0

    private fun open(code: String, path: String = "app/Http/Controllers/Home${calls++}Controller.php") =
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)

    private fun documentText(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    private fun unresolvedKeys(): List<String> =
        myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }.distinct()

    /** The values the key at the caret resolves to, one per locale holding it. */
    private fun resolvedValues(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        val reference = element?.references?.firstOrNull() as? PsiPolyVariantReference
        reference?.multiResolve(false).orEmpty().mapNotNull { it.element?.text?.unQuote() }
    }

    private fun foldedValues(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        PhpFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).map { it.placeholderText.orEmpty() }
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    fun renameRewritesTheNestedArrayKeyInEveryLocaleAndTheCalls() = laravelProject {
        myFixture.addFileToProject("app/Other.php", "<?php echo trans('messages.auth.failed');")
        open("<?php echo __('messages.auth.fai<caret>led');")
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "denied"
            override fun show(message: String, validator: InputValidator?): String = "denied"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        assertTrue(myFixture.editor.document.text.contains("__('messages.auth.denied')"), myFixture.editor.document.text)
        assertTrue(documentText("app/Other.php").contains("trans('messages.auth.denied')"), documentText("app/Other.php"))
        for (locale in listOf("en", "fr")) {
            val file = documentText("lang/$locale/messages.php")
            assertTrue(file.contains("'denied' =>") && !file.contains("'failed'"), file)
            assertTrue(file.contains("'auth' =>"), "the parent level keeps its name: $file")
        }
    }

    // ── Folding and inlay hints ───────────────────────────────────────────────

    @Test
    fun laravelCallsAreFolded() = laravelProject {
        open(
            """
            <?php
            echo __('messages.welcome');
            echo trans('messages.auth.failed');
            echo app('translator')->get('messages.welcome');
            echo __("Log in");
            echo __('messages.absent');
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("Welcome", "These credentials do not match.", "Welcome", "Sign in"), foldedValues())
    }

    @Test
    fun aStringOutsideATranslationCallIsNotFolded() = laravelProject {
        open("<?php echo strtoupper('messages.welcome'); \$request->get('messages.welcome');")
        Assertions.assertEquals(emptyList<String>(), foldedValues())
    }

    /** Blade injects `@lang('key')` and `{{ __('key') }}` as PHP: the editor folds the injected calls. */
    @Test
    fun bladeDirectivesAndEchoesAreFolded() = laravelProject {
        open("<p>@lang('messages.welcome')</p>\n<p>{{ __('messages.auth.failed') }}</p>", "resources/views/home.blade.php")
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val placeholders = myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
        assertTrue(placeholders.containsAll(listOf("Welcome", "These credentials do not match.")), "$placeholders")
    }

    /** Through the editor's own pass: it visits the view's elements, not the PHP injected into it. */
    @Test
    fun bladeDirectivesAndEchoesGetAnInlayHint() = laravelProject {
        open("<p>@lang('messages.welcome')</p>\n<p>{{ __('messages.auth.failed') }}</p>", "resources/views/hint.blade.php")
        myFixture.doHighlighting()
        val inlays = myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
        Assertions.assertEquals(2, inlays.size, "one hint per key")
    }

    @Test
    fun laravelCallsGetAnInlayHint() = laravelProject {
        open("<?php echo __('messages.welcome'); echo trans_choice('messages.auth.failed', 2);")
        val collector = I18nInlayHintsProvider().createCollector(myFixture.file, myFixture.editor) as SharedBypassCollector
        val sink = CapturingSink()
        ReadAction.run<RuntimeException> {
            PsiTreeUtil.processElements(myFixture.file) { collector.collectFromElement(it, sink); true }
        }
        Assertions.assertEquals(listOf("↦ Welcome", "↦ These credentials do not match."), sink.hints)
    }

    // ── JSON phrases ──────────────────────────────────────────────────────────

    @Test
    fun aJsonPhraseHoldingSeparatorsResolvesWhole() = laravelProject {
        open("<?php echo __('Sav<caret>ed.');")
        Assertions.assertEquals(listOf("Changes saved."), resolvedValues())
        open("<?php echo __('Hello, :na<caret>me', ['name' => 'Ada']);")
        Assertions.assertEquals(listOf("Hello, :name!"), resolvedValues())
    }

    @Test
    fun aJsonPhraseIsNeitherReportedNorLeftUnfolded() = laravelProject {
        open("<?php echo __('Saved.'); echo __('Hello, :name', ['name' => 'Ada']); echo __('messages.absent');")
        Assertions.assertEquals(listOf("absent"), unresolvedKeys())
        Assertions.assertEquals(listOf("Changes saved.", "Hello, :name!"), foldedValues())
    }

    @Test
    fun aKeyOfAPhpFileIsStillReadAsFileAndKey() = laravelProject {
        open("<?php echo __('messages.auth.fai<caret>led');")
        Assertions.assertEquals(listOf("These credentials do not match.", "Identifiants incorrects."), resolvedValues().sortedByDescending { it.startsWith("These") })
    }

    /** Records the text the inlay provider pushes, ignoring the parts of the tree not used. */
    private class CapturingBuilder : PresentationTreeBuilder {
        val text = StringBuilder()
        override fun text(text: String, actionData: InlayActionData?) {
            this.text.append(text)
        }

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder(this)
        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = builder(this)
        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit
        ) = Unit
    }

    private class CapturingSink : InlayTreeSink {
        val hints = mutableListOf<String>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            hints += CapturingBuilder().also(builder).text.toString()
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }
}
