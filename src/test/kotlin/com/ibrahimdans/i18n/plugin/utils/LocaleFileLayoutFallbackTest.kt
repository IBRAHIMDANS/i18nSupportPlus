package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The "one file per locale" layout — `locales/fr.json` + `locales/en.json`, no namespace —
 * used to resolve nothing at all.
 *
 * A key carrying no namespace requests none, so [LocalizationSourceService.findSources] fell
 * back to the default namespace (`translation`), which the localizations match against the
 * *file name*: no `translation.json`, no source, every key unresolved. Meanwhile
 * [LocalizationSourceService.findAllSources] read the very same project without trouble
 * through its locale heuristic — the fallback simply was not wired into `findSources`.
 */
class LocaleFileLayoutFallbackTest : PlatformBaseTest() {

    private fun findSources(namespaces: List<String>): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findSources(namespaces, project)
        }

    private fun addLocaleFiles() {
        addFileToProject("locales/fr.json", """{"dashboard": {"title": "Ma pharmacie"}}""")
        addFileToProject("locales/en.json", """{"dashboard": {"title": "My pharmacy"}}""")
    }

    @Test
    fun aKeyWithoutNamespaceFallsBackToTheWholeScan() = myFixture.runWithConfig(Config()) {
        addLocaleFiles()

        val sources = findSources(emptyList())

        Assertions.assertEquals(
            listOf("en.json", "fr.json"),
            sources.map { it.name }.sorted(),
            "both locale files must be reachable without any namespace"
        )
    }

    /**
     * The condition the whole fix rests on: an explicit namespace that matches no file is a
     * configuration error the user must see, not something to paper over with unrelated files.
     */
    @Test
    fun anExplicitNamespaceNeverFallsBack() = myFixture.runWithConfig(Config()) {
        addLocaleFiles()

        Assertions.assertTrue(
            findSources(listOf("common")).isEmpty(),
            "an unresolved namespace must stay unresolved"
        )
    }

    /** A project holding the default namespace resolves through it, the fallback never runs. */
    @Test
    fun theDefaultNamespaceKeepsPriorityOverTheFallback() =
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            addFileToProject("assets/translation.json", """{"dashboard": {"title": "Titre"}}""")
            addFileToProject("assets/other.json", """{"dashboard": {"title": "Autre"}}""")

            val sources = findSources(emptyList())

            Assertions.assertEquals(
                listOf("translation.json"),
                sources.map { it.name },
                "the file named after the default namespace wins; the fallback would add other.json"
            )
        }

    /** Anti-regression: a multi-namespace project addresses its files by name as before. */
    @Test
    fun namespacedProjectsAreUnaffected() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}}""")
        addFileToProject("locales/en/auth.json", """{"login": "Log in"}""")

        Assertions.assertEquals(
            listOf("common.json"),
            findSources(listOf("common")).map { it.name },
            "a requested namespace resolves to its own file only"
        )
    }
}
