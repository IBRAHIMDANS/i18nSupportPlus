package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UntranslatedValueInspection
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The message files a Paraglide project declares in `project.inlang/settings.json`, wherever its
 * `pathPattern` puts them, each with its locale — and one set per inlang project of a monorepo.
 */
class ParaglideTechnologyTest : PlatformBaseTest() {

    private fun settings(pattern: String, base: String = "en") =
        """{ "baseLocale": "$base", "locales": ["en", "fr"], "plugin.inlang.messageFormat": { "pathPattern": "$pattern" } }"""

    /** The sources found, as `locale → file path` relative to the VFS root. */
    private fun sources(): Set<Pair<String?, String>> = ReadAction.compute<Set<Pair<String?, String>>, RuntimeException> {
        ParaglideTechnology().findSourcesByConfiguration(project)
            .map { it.locale to it.tree!!.value().containingFile.virtualFile.path.substringAfter("/src/") }
            .toSet()
    }

    @Test
    fun aPatternOutsideMessagesIsFollowed() {
        addFileToProject("project.inlang/settings.json", settings("./translations/{locale}.json"))
        addFileToProject("translations/en.json", """{"hello": "Hello"}""")
        addFileToProject("translations/fr.json", """{"hello": "Bonjour"}""")
        assertEquals(setOf("en" to "translations/en.json", "fr" to "translations/fr.json"), sources())
    }

    @Test
    fun eachInlangProjectOfAMonorepoIsRead() {
        addFileToProject("apps/web/project.inlang/settings.json", settings("./messages/{locale}.json"))
        addFileToProject("apps/web/messages/en.json", """{"a": "A"}""")
        addFileToProject("apps/admin/project.inlang/settings.json", settings("./i18n/{locale}.json", base = "fr"))
        addFileToProject("apps/admin/i18n/fr.json", """{"b": "B"}""")
        assertEquals(setOf("en" to "apps/web/messages/en.json", "fr" to "apps/admin/i18n/fr.json"), sources())
    }

    @Test
    fun invalidSettingsGiveNoSourceAndNoError() {
        addFileToProject("project.inlang/settings.json", "{ not json")
        addFileToProject("messages/en.json", """{"a": "A"}""")
        assertTrue(sources().isEmpty())
        assertNull(ParaglideTechnology.baseLocale(project))
    }

    @Test
    fun theBaseLocaleIsExposed() {
        addFileToProject("project.inlang/settings.json", settings("./messages/{locale}.json", base = "fr"))
        assertEquals("fr", ParaglideTechnology.baseLocale(project))
    }

    /** A `settings.json` of another tool is no inlang project. */
    @Test
    fun aSettingsFileOutsideProjectInlangIsIgnored() {
        addFileToProject(".vscode/settings.json", settings("./messages/{locale}.json"))
        addFileToProject("messages/en.json", """{"a": "A"}""")
        assertTrue(sources().isEmpty())
    }

    /**
     * A message file the scan finds and `settings.json` declares is one source, not two: listed twice,
     * `en.json` found itself as the reference locale's file, and every value read as untranslated.
     */
    @Test
    fun aDeclaredMessageFileIsOneSourceAndTheReferenceIsNotItsOwnCopy() {
        addFileToProject("project.inlang/settings.json", settings("./messages/{locale}.json"))
        // Sentences: a single word may well be the same in every language, and is never reported.
        addFileToProject("messages/en.json", """{"hello": "Hello there world", "only": "Only in English"}""")
        addFileToProject("messages/fr.json", """{"hello": "Hello there world"}""")
        val paths = ReadAction.compute<List<String>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project).map { it.displayPath }
        }
        assertEquals(paths.distinct(), paths)
        myFixture.enableInspections(UntranslatedValueInspection::class.java)
        val untranslated = "Value identical to the reference locale: not translated?"
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("messages/en.json"))
        assertTrue(myFixture.doHighlighting().none { it.description == untranslated }, "the reference itself")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("messages/fr.json"))
        assertEquals(1, myFixture.doHighlighting().count { it.description == untranslated }, "a value copied from en")
    }
}
