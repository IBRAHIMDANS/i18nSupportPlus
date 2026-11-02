package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A module laid out one folder per namespace (`locales/{ns}/{lang}.json`): `locales/common/en.json` is
 * the `common` namespace's file although it is named after its locale. Looked up by file name alone,
 * `t('common:save')` found no source, and a key naming no namespace resolved against every namespace.
 */
class NamespaceFolderResolutionTest : PlatformBaseTest() {

    private fun config(defaultNs: String = "common") =
        Config(defaultNs = defaultNs, modules = listOf(ModuleConfig(name = "app", pathTemplate = "locales/{ns}/{lang}.json")))

    private fun addTranslations() {
        myFixture.addFileToProject("locales/common/en.json", """{"save": "Save"}""")
        myFixture.addFileToProject("locales/common/fr.json", """{"save": "Enregistrer"}""")
        myFixture.addFileToProject("locales/auth/en.json", """{"title": "Sign in"}""")
        myFixture.addFileToProject("locales/auth/fr.json", """{"title": "Connexion"}""")
    }

    private fun sourcePaths(namespaces: List<String>): List<String> =
        project.service<LocalizationSourceService>().findSources(namespaces, project)
            .map { it.displayPath.substringAfter("locales/") }.sorted()

    @Test
    fun aPrefixedKeyFindsItsNamespaceFolder() = myFixture.runWithConfig(config()) {
        addTranslations()

        assertEquals(listOf("common/en.json", "common/fr.json"), sourcePaths(listOf("common")))
        assertEquals(listOf("auth/en.json", "auth/fr.json"), sourcePaths(listOf("auth")))
    }

    @Test
    fun aKeyNamingNoNamespaceResolvesInTheDefaultNamespaceOnly() = myFixture.runWithConfig(config()) {
        addTranslations()

        assertEquals(listOf("common/en.json", "common/fr.json"), sourcePaths(emptyList()))
    }

    /** The annotator: `common:save` resolves, `auth:save` is a missing key of a known namespace, not an unknown one. */
    @Test
    fun theAnnotatorResolvesKeysOfANamespaceFolder() = myFixture.runWithConfig(config()) {
        addTranslations()
        val unresolvedNs = PluginBundle.getMessage("annotator.unresolved.ns")

        myFixture.configureByText("Page.js", "t('common:save'); t('auth:title'); t('save');")
        val resolved = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(resolved.any { it.startsWith(unresolvedNs) }, "every namespace is known: $resolved")
        assertFalse(resolved.contains(PluginBundle.getMessage("annotator.unresolved.key")), "every key resolves: $resolved")

        myFixture.configureByText("Other.js", "t('auth:save');")
        val missing = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(missing.contains(PluginBundle.getMessage("annotator.unresolved.key")), "auth holds no save: $missing")
    }
}
