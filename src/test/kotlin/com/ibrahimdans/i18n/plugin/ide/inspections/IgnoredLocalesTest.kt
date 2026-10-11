package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.MissingTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A locale in progress or abandoned, set in *Locales not checked for missing keys*, raises no "missing" alert. */
class IgnoredLocalesTest : PlatformBaseTest() {

    private fun missingKeyWarnings(path: String, content: String): List<String> {
        myFixture.enableInspections(MissingTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject(path, content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .mapNotNull { it.description }
            .filter { it.startsWith("Key ") }
    }

    @Test
    fun anIgnoredLocaleHasNoMissingKeyWarning() = myFixture.runWithConfig(Config(ignoredLocales = "de, pt-BR")) {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title", "bye": "Bye"}""")
        assertTrue(missingKeyWarnings("locales/de/common.json", """{"title": "Titel"}""").isEmpty())
    }

    @Test
    fun theOtherLocalesAreStillChecked() = myFixture.runWithConfig(Config(ignoredLocales = "de")) {
        myFixture.addFileToProject("locales/en/common.json", """{"title": "Title", "bye": "Bye"}""")
        assertEquals(1, missingKeyWarnings("locales/fr/common.json", """{"title": "Titre"}""").size)
    }

    @Test
    fun aLanguageCoversItsRegionalFiles() = myFixture.runWithConfig(Config(ignoredLocales = "de")) {
        myFixture.addFileToProject("locales/en-US/common.json", """{"title": "Title", "bye": "Bye"}""")
        assertTrue(missingKeyWarnings("locales/de-DE/common.json", """{"title": "Titel"}""").isEmpty())
    }

    @Test
    fun anIgnoredLocaleDoesNotMakeAKeyPartiallyTranslated() =
        myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true, ignoredLocales = "ru")) {
            myFixture.addFileToProject("en/test.json", """{"root": {"sub": {"base": "Defined"}}}""")
            myFixture.addFileToProject("ru/test.json", """{"root": {"another": "value"}}""")
            myFixture.configureByText("sample.tsx", TsxCodeGenerator().generate("'test:root.sub.ba<caret>se'"))
            val messages = myFixture.doHighlighting().mapNotNull { it.description }
            assertFalse(PluginBundle.getMessage("annotator.partially.translated") in messages, "$messages")
        }

    @Test
    fun aKeyMissingFromACheckedLocaleIsStillPartiallyTranslated() =
        myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true, ignoredLocales = "de")) {
            myFixture.addFileToProject("en/test.json", """{"root": {"sub": {"base": "Defined"}}}""")
            myFixture.addFileToProject("ru/test.json", """{"root": {"another": "value"}}""")
            myFixture.configureByText("sample.tsx", TsxCodeGenerator().generate("'test:root.sub.ba<caret>se'"))
            val messages = myFixture.doHighlighting().mapNotNull { it.description }
            assertTrue(PluginBundle.getMessage("annotator.partially.translated") in messages, "$messages")
        }

    @Test
    fun localesMatchLikeLocaleMatching() {
        val config = Config(ignoredLocales = "de; en-GB pt_br")
        assertTrue(config.ignoresLocale("de"))
        assertTrue(config.ignoresLocale("de-AT"))
        assertTrue(config.ignoresLocale("en_GB"))
        assertTrue(config.ignoresLocale("pt-BR"))
        assertFalse(config.ignoresLocale("en-US"))
        assertFalse(config.ignoresLocale("en"))
        assertFalse(Config().ignoresLocale("de"))
    }
}
