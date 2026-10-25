package com.ibrahimdans.i18n.extensions.technology.nextintl

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * next-intl's `t` has methods taking a key: `t.rich('terms', { link: … })` and `t.markup` for
 * messages holding tags, `t.raw('list')` for the message as written, `t.has('title')` to test that
 * it exists. They were not read at all: no check, no completion, no navigation, no usage counted.
 */
class NextIntlMethodsTest : PlatformBaseTest() {

    private companion object {
        const val MESSAGES = """{"Home": {"title": "Welcome", "terms": "Read the <link>terms</link>", "features": ["Fast", "Free"]}, "zzOther": {"title": "Other"}}"""
    }

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 30, foldingEnabled = true)

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun withMessages(body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("messages/en.json", MESSAGES)
        body()
    }

    private fun component(call: String) = """
        import { useTranslations } from 'next-intl';
        export default function Home() {
            const t = useTranslations('Home');
            return $call;
        }
    """.trimIndent()

    private fun page(call: String) = """
        import { getTranslations } from 'next-intl/server';
        export default async function Page() {
            const t = await getTranslations('Home');
            return $call;
        }
    """.trimIndent()

    private fun referenceAtCaret(): I18nReference? = ReadAction.compute<I18nReference?, RuntimeException> {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)
        listOfNotNull(leaf?.parent, leaf).firstNotNullOfOrNull { it.references.filterIsInstance<I18nReference>().firstOrNull() }
    }

    private fun usagesOf(key: String): Int =
        TableViewModel().countUsages(project, listOf(TranslationRow(key, mapOf("en" to "value"))))
            .first().usageCount

    // ── Checks ────────────────────────────────────────────────────────────────

    @Test
    fun everyMessageMethodResolvesItsKeyUnderUseTranslations() = withMessages {
        for (method in listOf("rich", "markup", "raw")) {
            myFixture.configureByText("Home$method.tsx", component("t.$method('terms', { link: (c) => c })"))
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    @Test
    fun aMissingKeyIsReportedUnderGetTranslations() = withMessages {
        myFixture.configureByText("Page.tsx", page("t.rich('<error descr=\"Unresolved key\">absent</error>', {})"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun rawMayReadAnArray() = withMessages {
        myFixture.configureByText("Home.tsx", component("t.raw('features')"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun hasIsNeverReportedMissing() = withMessages {
        myFixture.configureByText("Home.tsx", component("t.has('absent') ? t('title') : null"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun aMethodOfAnotherObjectIsNotAKey() = withMessages {
        myFixture.configureByText("Home.tsx", component("toast.rich('absent') && t.rich('terms', {})"))
        myFixture.checkHighlighting(true, true, true, true)
        myFixture.configureByText("Hook.tsx", "const toast = makeToast(); toast.raw('absent');")
        myFixture.checkHighlighting(true, true, true, true)
    }

    // ── Completion, folding, navigation ──────────────────────────────────────

    @Test
    fun completionOffersTheNamespaceKeys() = withMessages {
        myFixture.configureByText("Home.tsx", component("t.rich('<caret>', {})"))
        myFixture.complete(CompletionType.BASIC, 1)
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("terms" in offered, "the namespace's keys must be offered: $offered")
        assertFalse("zzOther" in offered, "a key outside the namespace must not be offered: $offered")
    }

    @Test
    fun richIsFolded() = withMessages {
        myFixture.configureByText("Home.tsx", component("t.markup('title', {})"))
        val placeholders = ReadAction.compute<List<String>, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).map { it.placeholderText.orEmpty() }
        }
        assertEquals(listOf("Welcome"), placeholders)
    }

    @Test
    fun richAndHasLeadToTheMessage() = withMessages {
        for (method in listOf("rich", "has")) {
            myFixture.configureByText("Home$method.tsx", component("t.$method('ti<caret>tle')"))
            val reference = referenceAtCaret()
            assertNotNull(reference, "no reference on t.$method")
            assertEquals("Welcome", ReadAction.compute<String?, RuntimeException> { reference!!.resolve()?.text?.unQuote() })
        }
    }

    @Test
    fun aMethodOfAnotherObjectHasNoReference() = withMessages {
        myFixture.configureByText("Home.tsx", component("toast.rich('ti<caret>tle')"))
        assertNull(referenceAtCaret())
    }

    // ── Usages and rename ─────────────────────────────────────────────────────

    @Test
    fun eachMethodCountsAsAUsage() = withMessages {
        myFixture.addFileToProject("src/A.tsx", component("t.rich('terms', {})"))
        myFixture.addFileToProject("src/B.tsx", page("t.has('title')"))

        assertEquals(1, usagesOf("Home.terms"))
        assertEquals(1, usagesOf("Home.title"))
        assertEquals(0, usagesOf("zzOther.title"), "the same tail under another namespace is not a usage")
    }

    @Test
    fun renameRewritesTheMethodCalls() = withMessages {
        myFixture.addFileToProject("src/Rich.tsx", component("t.rich('title', {})"))
        myFixture.configureByText("Home.tsx", component("t('ti<caret>tle')"))
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "heading"
            override fun show(message: String, validator: InputValidator?): String = "heading"
        })

        RenameI18nKeyHandler().invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)

        assertTrue(myFixture.editor.document.text.contains("t('heading')"), myFixture.editor.document.text)
        val rich = ReadAction.compute<String, RuntimeException> {
            PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/Rich.tsx"))!!.text
        }
        assertTrue(rich.contains("t.rich('heading', {})"), rich)
    }
}
