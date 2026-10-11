package com.ibrahimdans.i18n.extensions.technology.nextintl

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.intellij.codeInsight.completion.CompletionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * next-intl's Server Components take their `t` from `await getTranslations('Home')` — or
 * `getTranslations({ locale, namespace: 'Home' })` — instead of `useTranslations('Home')`.
 * Only the hook was read, so `t('title')` there was looked up without its namespace: reported as
 * unresolved, completed against the whole file, and its key shown as an orphan.
 */
class NextIntlGetTranslationsTest : PlatformBaseTest() {

    private companion object {
        const val UNUSED_MSG = "No reference to this key found in code"
        const val MESSAGES = """{"Home": {"title": "Welcome", "zzSubtitle": "Hi"}, "zzOther": {"title": "Other"}}"""
    }

    private fun page(initializer: String, call: String) = """
        import { getTranslations } from 'next-intl/server';
        export default async function Page({ locale }) {
            const t = $initializer;
            return t($call);
        }
    """.trimIndent()

    private fun addMessages() = myFixture.addFileToProject("messages/en.json", MESSAGES)

    // ── Resolution ────────────────────────────────────────────────────────────

    @Test
    fun awaitedStringNamespaceResolvesTheKey() {
        addMessages()
        myFixture.configureByText("Page.tsx", page("await getTranslations('Home')", "'title'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun namespaceOptionResolvesTheKey() {
        addMessages()
        myFixture.configureByText("Page.tsx", page("await getTranslations({ locale, namespace: 'Home' })", "'title'"))
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun missingKeyUnderGetTranslationsIsStillReported() {
        addMessages()
        myFixture.configureByText(
            "Page.tsx",
            page("await getTranslations('Home')", "'<error descr=\"Unresolved key\">absent</error>'")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    // ── Completion ────────────────────────────────────────────────────────────

    @Test
    fun completionOffersTheNamespaceLevel() {
        addMessages()
        myFixture.configureByText("Page.tsx", page("await getTranslations('Home')", "'<caret>'"))
        myFixture.complete(CompletionType.BASIC, 1)
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("zzSubtitle" in offered, "the namespace's keys must be offered: $offered")
        assertFalse("zzOther" in offered, "a key outside the namespace must not be offered: $offered")
    }

    // ── Usages ────────────────────────────────────────────────────────────────

    private fun usagesOf(key: String): Int =
        TableViewModel().countUsages(project, listOf(TranslationRow(key, mapOf("en" to "value"))))
            .first().usageCount

    @Test
    fun keyUnderGetTranslationsIsCounted() {
        addMessages()
        myFixture.configureByText("Page.tsx", page("await getTranslations('Home')", "'title'"))

        assertEquals(1, usagesOf("Home.title"))
        assertEquals(0, usagesOf("zzOther.title"), "the same tail under another namespace is not a usage")
    }

    @Test
    fun keyUnderTheNamespaceOptionIsCounted() {
        addMessages()
        myFixture.configureByText("Page.tsx", page("await getTranslations({ locale, namespace: 'Home' })", "'title'"))

        assertEquals(1, usagesOf("Home.title"))
    }

    @Test
    fun keyUsedUnderGetTranslationsIsNotAnOrphan() {
        val messages = myFixture.addFileToProject("messages/en.json", """{"Home": {"title": "Welcome", "unused": "x"}}""")
        myFixture.addFileToProject("src/Page.tsx", page("await getTranslations('Home')", "'title'"))
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(messages.virtualFile)

        val flagged = myFixture.doHighlighting().filter { it.description == UNUSED_MSG }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset).trim('"') }
        assertEquals(listOf("unused"), flagged)
    }
}
