package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.ReadAction
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
}
