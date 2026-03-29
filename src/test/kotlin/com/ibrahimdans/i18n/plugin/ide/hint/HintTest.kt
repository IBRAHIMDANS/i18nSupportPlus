package com.ibrahimdans.i18n.plugin.ide.hint

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.code.*
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HintTest: PlatformBaseTest() {

    /**
     * Reproduces: hint not shown when namespace comes from useTranslation('ns') (no explicit ns: prefix in key).
     * The HintProvider must extract the namespace from the useTranslation hook, not only from the key string.
     */
    @Test
    fun testHintWithUseTranslationNamespace() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/auth.${tg.ext()}", """{"login":{"title":"Sign in"}}""")
        myFixture.configureByText("LoginForm.tsx", """
            import { useTranslation } from 'react-i18next';
            export default function LoginForm() {
                const { t } = useTranslation('auth');
                return t('login.<caret>title');
            }
        """.trimIndent())
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull("Hint must not be null when namespace comes from useTranslation", hint)
            assertTrue("Hint must contain the translation value", hint!!.contains("Sign in"))
        }
    }

    /**
     * Reproduces: hint not shown when namespace comes from useTranslation(['ns']) (array form).
     */
    @Test
    fun testHintWithUseTranslationArrayNamespace() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/auth.${tg.ext()}", """{"login":{"title":"Sign in"}}""")
        myFixture.configureByText("LoginFormArray.tsx", """
            import { useTranslation } from 'react-i18next';
            export default function LoginForm() {
                const { t } = useTranslation(['auth']);
                return t('login.<caret>title');
            }
        """.trimIndent())
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull("Hint must not be null when namespace comes from useTranslation array", hint)
            assertTrue("Hint must contain the translation value", hint!!.contains("Sign in"))
        }
    }

    @Test
    fun testSingleHint() {
        val cgs = listOf(JsCodeGenerator(), TsCodeGenerator(), JsxCodeGenerator(), TsxCodeGenerator(), PhpSingleQuoteCodeGenerator(), PhpDoubleQuoteCodeGenerator())
        val translation = "translation here"
        val tg = JsonTranslationGenerator()
        cgs.forEachIndexed {
            index, cg ->
                myFixture.addFileToProject("test${index}.${tg.ext()}", tg.generateContent("root", "first", "second", translation))
                myFixture.configureByText("content.${cg.ext()}", cg.generate("\"test${index}:root.first.<caret>second\""))
                read {
                    val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
                    val hint = HintProvider().generateDoc(null, codeElement)
                    assertTrue("Hint should contain the translation", hint?.contains(translation) == true)
                }
        }
    }

    /**
     * Verifies that when multiple translation files exist for different languages,
     * the hint displays all translations grouped by language folder.
     * Tests using HintProvider directly to avoid multi-resolve ambiguity in elementAtCaret.
     */
    @Test
    fun testMultiLanguageHint() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/multi.${tg.ext()}", tg.generateContent("root", "first", "second", "Hello"))
        myFixture.addFileToProject("fr/multi.${tg.ext()}", tg.generateContent("root", "first", "second", "Bonjour"))
        myFixture.addFileToProject("de/multi.${tg.ext()}", tg.generateContent("root", "first", "second", "Hallo"))
        myFixture.configureByText("content_multi.js", JsCodeGenerator().generate("\"multi:root.first.<caret>second\"", 0))
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull(hint)
            assertTrue("Hint should contain 'en' language", hint!!.contains("en"))
            assertTrue("Hint should contain 'fr' language", hint.contains("fr"))
            assertTrue("Hint should contain 'de' language", hint.contains("de"))
            assertTrue("Hint should contain English translation", hint.contains("Hello"))
            assertTrue("Hint should contain French translation", hint.contains("Bonjour"))
            assertTrue("Hint should contain German translation", hint.contains("Hallo"))
        }
    }

    @Test
    fun testMultiLanguageHintFormat() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/fmt.${tg.ext()}", tg.generateContent("root", "first", "second", "Hello"))
        myFixture.addFileToProject("fr/fmt.${tg.ext()}", tg.generateContent("root", "first", "second", "Bonjour"))
        myFixture.configureByText("content_fmt.js", JsCodeGenerator().generate("\"fmt:root.first.<caret>second\"", 0))
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull(hint)
            // Verify table format with locale codes and translation values
            assertTrue("Hint should be a table", hint!!.contains("<table"))
            assertTrue("Hint should contain English translation", hint.contains("Hello"))
            assertTrue("Hint should contain French translation", hint.contains("Bonjour"))
        }
    }

    /**
     * Reproduces bug 1: explicit namespace prefix in key ("dashboard:stats.users.count")
     * when useTranslation() is called with no namespace argument.
     * i18next supports "namespace:key" syntax — the plugin must resolve via the namespace
     * extracted from the key itself, not from useTranslation().
     */
    @Test
    fun testExplicitNamespaceInKeyWithEmptyUseTranslation() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/dashboard.${tg.ext()}", """{"stats":{"users":{"count":"42 users"}}}""")
        myFixture.addFileToProject("fr/dashboard.${tg.ext()}", """{"stats":{"users":{"count":"42 utilisateurs"}}}""")
        myFixture.configureByText("Navigation.tsx", """
            import { useTranslation } from 'react-i18next';
            export default function Navigation() {
                const { t } = useTranslation();
                return t('dashboard:stats.users.<caret>count');
            }
        """.trimIndent())
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull("Hint must not be null for explicit namespace key with empty useTranslation", hint)
            assertTrue("Hint must contain English translation", hint!!.contains("42 users"))
            assertTrue("Hint must contain French translation", hint.contains("42 utilisateurs"))
        }
    }

    /**
     * Reproduces: with useTranslation(['dashboard', 'common']), a key that exists only in
     * 'dashboard' must not produce empty rows ("—") for the 'common' namespace.
     * Expected: 2 rows (en + fr for dashboard), no empty entries.
     */
    @Test
    fun testMultiNamespaceHintNoEmptyRows() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/dashboard.${tg.ext()}", """{"stats":{"files":{"count":"42 files"}}}""")
        myFixture.addFileToProject("fr/dashboard.${tg.ext()}", """{"stats":{"files":{"count":"42 fichiers"}}}""")
        myFixture.addFileToProject("en/common.${tg.ext()}", """{"title":"App"}""")
        myFixture.addFileToProject("fr/common.${tg.ext()}", """{"title":"Application"}""")
        myFixture.configureByText("Dashboard.tsx", """
            import { useTranslation } from 'react-i18next';
            export default function Dashboard() {
                const { t } = useTranslation(['dashboard', 'common']);
                return t('stats.files.<caret>count');
            }
        """.trimIndent())
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            assertNotNull(hint)
            assertTrue("Hint must contain English translation", hint!!.contains("42 files"))
            assertTrue("Hint must contain French translation", hint.contains("42 fichiers"))
            assertFalse("Hint must not contain empty entries", hint.contains("—"))
            // Exactly 2 rows: one per locale, only for the namespace that resolves
            val rowCount = "<tr>".toRegex().findAll(hint).count()
            assertEquals("Expected exactly 2 rows (en + fr for dashboard only)", 2, rowCount)
        }
    }

    @Test
    fun testSingleLanguageFolderHintShowsLocale() {
        val tg = JsonTranslationGenerator()
        myFixture.addFileToProject("en/single.${tg.ext()}", tg.generateContent("root", "first", "second", "Hello"))
        myFixture.configureByText("content_single.js", JsCodeGenerator().generate("\"single:root.first.<caret>second\"", 0))
        read {
            val codeElement = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, codeElement)
            // Even a single locale is shown in table format with its locale code
            assertNotNull(hint)
            assertTrue("Hint should contain the translation", hint!!.contains("Hello"))
            assertTrue("Hint should contain 'en' locale code", hint.contains("en"))
        }
    }
}
