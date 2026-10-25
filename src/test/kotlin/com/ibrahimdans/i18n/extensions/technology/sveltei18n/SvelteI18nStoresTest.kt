package com.ibrahimdans.i18n.extensions.technology.sveltei18n

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * svelte-i18n's stores besides `$_('key')`: its aliases `$t` and `$format`, `$json('nav')` reading
 * the messages under a key, and the descriptor form `$_({ id: 'key', values })`. Only `$_('key')`
 * was read: `$format` was no call at all, `$json('nav')` was reported as a reference to an object,
 * and every string of a descriptor — `default` included — was taken for a key.
 */
class SvelteI18nStoresTest : PlatformBaseTest() {

    private companion object {
        const val MESSAGES = """{"nav": {"home": "Home", "settings": "Settings"}, "greeting": "Hello {name}"}"""
    }

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun withMessages(body: () -> Unit) = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", MESSAGES)
        body()
    }

    private fun script(call: String) = "import { _, t, format, json } from 'svelte-i18n';\nexport const label = $call;"

    private fun errors(): List<String?> =
        myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }

    private fun referenceAtCaret(): I18nReference? = ReadAction.compute<I18nReference?, RuntimeException> {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)
        listOfNotNull(leaf?.parent, leaf).firstNotNullOfOrNull { it.references.filterIsInstance<I18nReference>().firstOrNull() }
    }

    private fun completions(call: String): List<String> {
        myFixture.configureByText("Complete.js", script(call))
        myFixture.complete(CompletionType.BASIC, 1)
        return myFixture.lookupElementStrings.orEmpty()
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    @Test
    fun everyAliasResolvesItsKey() = withMessages {
        for (store in listOf("\$_", "\$t", "\$format")) {
            myFixture.configureByText("Resolved.js", script("$store('nav.home')"))
            Assertions.assertEquals(emptyList<String?>(), errors(), store)
        }
    }

    @Test
    fun everyAliasReportsAMissingKey() = withMessages {
        for (store in listOf("\$t", "\$format")) {
            myFixture.configureByText("Missing.js", script("$store('nav.<error descr=\"Unresolved key\">absent</error>')"))
            myFixture.checkHighlighting(true, false, false, true)
        }
    }

    @Test
    fun jsonReadsAnObject() = withMessages {
        myFixture.configureByText("Json.js", script("\$json('nav')"))
        Assertions.assertEquals(emptyList<String?>(), errors())
        // The same key through `$_` names an object where a message is expected.
        myFixture.configureByText("Object.js", script("\$_('nav')"))
        Assertions.assertEquals(listOf("Reference to object"), errors())
    }

    @Test
    fun jsonReportsAMissingKey() = withMessages {
        myFixture.configureByText("Json.js", script("\$json('<error descr=\"Unresolved key\">absent</error>')"))
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun aDescriptorIdIsTheKey() = withMessages {
        myFixture.configureByText("Descriptor.js", script("\$_({ id: 'nav.home', default: 'Fallback', values: { name: 'Bob' } })"))
        Assertions.assertEquals(emptyList<String?>(), errors())
        myFixture.configureByText("Missing.js", script("\$format({ id: '<error descr=\"Unresolved key\">absent</error>', default: 'Fallback' })"))
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun theOptionsHoldNoKey() = withMessages {
        myFixture.configureByText("Options.js", script("\$_('greeting', { default: 'Fallback', values: { name: 'Bob' } })"))
        Assertions.assertEquals(emptyList<String?>(), errors())
    }

    // ── Completion and navigation ─────────────────────────────────────────────

    @Test
    fun completionOffersTheKeys() = withMessages {
        for (call in listOf("\$format('<caret>')", "\$t('<caret>')", "\$_({ id: '<caret>' })", "\$json('<caret>')")) {
            val offered = completions(call)
            assertTrue(offered.containsAll(listOf("nav", "greeting")), "$call -> $offered")
        }
    }

    @Test
    fun aDescriptorIdLeadsToTheMessage() = withMessages {
        for (call in listOf("\$_({ id: 'nav.ho<caret>me' })", "\$format('nav.ho<caret>me')")) {
            myFixture.configureByText("Navigate.js", script(call))
            val reference = referenceAtCaret()
            assertNotNull(reference, call)
            Assertions.assertEquals("Home", ReadAction.compute<String?, RuntimeException> { reference!!.resolve()?.text?.unQuote() }, call)
        }
    }

    @Test
    fun renameRewritesTheDescriptorAndTheAliases() = withMessages {
        myFixture.addFileToProject("src/Other.js", script("\$_({ id: 'greeting', values: { name: 'Bob' } }) + \$format('greeting')"))
        myFixture.configureByText("Home.js", script("\$t('gree<caret>ting')"))
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "welcome"
            override fun show(message: String, validator: InputValidator?): String = "welcome"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        assertTrue(myFixture.editor.document.text.contains("\$t('welcome')"), myFixture.editor.document.text)
        val other = ReadAction.compute<String, RuntimeException> {
            PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/Other.js"))!!.text
        }
        assertTrue(other.contains("\$_({ id: 'welcome'") && other.contains("\$format('welcome')"), other)
    }

    // ── Inside a component ────────────────────────────────────────────────────

    @Test
    fun aStoreResolvesInTheMarkupOfAComponent() = withMessages {
        myFixture.configureByText(
            "Nav.svelte",
            """
            <script>
              import { t, json } from 'svelte-i18n';
            </script>
            <a href="/">{${'$'}t('nav.home')}</a>
            <p>{${'$'}json('nav')}</p>
            <p>{${'$'}t({ id: 'nav.<error descr="Unresolved key">absent</error>' })}</p>
            """.trimIndent()
        )
        Assertions.assertEquals("Svelte", myFixture.file.fileType.name, "the component must be parsed as Svelte")
        myFixture.checkHighlighting(true, false, false, true)
    }
}
