package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.CodeDefaults
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ConfigDiagnostic
import com.ibrahimdans.i18n.plugin.ide.settings.ConfigDiagnostics
import com.ibrahimdans.i18n.plugin.ide.settings.ConfigIssue
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.namespaceFacts
import com.ibrahimdans.i18n.plugin.ide.settings.useDefaultNamespace
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A blank default namespace in the settings — `.idea/i18nSettings.xml` is per machine, and one of
 * them held `defaultNs=""` — took `translation`, which no file was named after: every `t('…')`
 * without a prefix was red while `src/i18n.ts` said `defaultNS: 'common'`. The plugin now reads the
 * project's i18next initialisation, found by what it calls rather than by its name.
 */
class I18nextInitDiscoveryTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun addInit(options: String, path: String = "src/i18n.ts") = addFileToProject(
        path,
        """
        import i18n from 'i18next';
        import { initReactI18next } from 'react-i18next';

        i18n.use(initReactI18next).init({ $options });
        export default i18n;
        """.trimIndent()
    )

    private fun addTranslations() {
        addFileToProject("public/locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("public/locales/en/auth.json", """{"login": "Log in"}""")
    }

    private fun unresolvedKeysIn(calls: String): List<String> {
        val file = addFileToProject(
            "src/App.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const A = () => { const { t } = useTranslation(); return [$calls]; };"
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
    }

    private fun config(): Config = Settings.getInstance(project).config()

    @Test
    fun aBlankDefaultNamespaceIsReadFromTheInitialisation() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addTranslations()
        addInit("defaultNS: 'common', ns: ['common', 'auth'], fallbackLng: 'en'")

        assertEquals(listOf("common"), config().defaultNamespaces())
        assertTrue(unresolvedKeysIn("t('button.save')").isEmpty())
    }

    @Test
    fun aFilledDefaultNamespaceKeepsItsValue() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addTranslations()
        addInit("defaultNS: 'common'")

        assertEquals(listOf("translation"), config().defaultNamespaces())
        assertNull(Settings.getInstance(project).codeDefaults(), "nothing is blank: the code is not even read")
    }

    @Test
    fun aListOfDefaultNamespacesIsReadAsAList() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addInit("defaultNS: ['common', 'shared']")

        assertEquals(listOf("common", "shared"), config().defaultNamespaces())
    }

    @Test
    fun aComputedValueIsNotRead() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addFileToProject("src/namespaces.ts", "export const NAMESPACES = ['common'];")
        addInit("defaultNS: NAMESPACES[0], fallbackLng: getLocale()")

        assertEquals(listOf("translation"), config().defaultNamespaces())
        assertNull(Settings.getInstance(project).codeDefaults())
    }

    /** `createInstance(options)` and a JavaScript file, under any name, count as well. */
    @Test
    fun anInstanceCreatedWithItsOptionsIsFoundUnderAnyName() = myFixture.runWithConfig(Config(defaultNs = "", foldingPreferredLanguage = "")) {
        addFileToProject(
            "src/lib/setup-translations.js",
            "import i18next from 'i18next';\nexport const i18n = i18next.createInstance({ defaultNS: 'app', fallbackLng: ['fr', 'en'] });"
        )

        assertEquals(CodeDefaults("src/lib/setup-translations.js", listOf("app"), "fr"), Settings.getInstance(project).codeDefaults())
        assertEquals("fr", config().foldingPreferredLanguage)
    }

    @Test
    fun theI18nextCliConfigurationIsReadWithoutAnInitialisation() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addFileToProject(
            "i18next.config.ts",
            "import { defineConfig } from 'i18next-cli';\nexport default defineConfig({ locales: ['en'], extract: { defaultNS: 'common' } });"
        )

        assertEquals(listOf("common"), config().defaultNamespaces())
    }

    /** A file importing i18next for its types, and calling some other `init`, is not taken for it. */
    @Test
    fun anotherInitIsNotTheInitialisation() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addFileToProject("src/analytics.ts", "import type { TFunction } from 'i18next';\nanalytics.init('key');")

        assertNull(Settings.getInstance(project).codeDefaults())
    }

    /** Saving the settings, or a module, never writes what the code says into them. */
    @Test
    fun savingTheSettingsKeepsTheBlank() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addInit("defaultNS: 'common'")
        val settings = Settings.getInstance(project)

        settings.setConfig(settings.config().copy(searchInProjectOnly = false))

        assertEquals("", settings.defaultNs)
        assertEquals(listOf("common"), settings.config().defaultNamespaces())
    }

    @Test
    fun aDefaultNamespaceWithoutFileIsReportedAndTheActionWritesTheSetting() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addTranslations()
        addInit("defaultNS: 'common'")

        val diagnostic = ConfigDiagnostics.defaultNamespaceWithoutFile(config(), namespaceFacts(project, config()))
        assertEquals(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_NO_FILE, path = "translation", suggestion = "common", origin = "src/i18n.ts"), diagnostic)

        useDefaultNamespace(project, diagnostic!!.suggestion!!)

        assertEquals("common", Settings.getInstance(project).defaultNs)
        assertTrue(unresolvedKeysIn("t('button.save')").isEmpty())
    }

    @Test
    fun theSettingsPageSaysWhereABlankDefaultNamespaceWasRead() = myFixture.runWithConfig(Config(defaultNs = "")) {
        addTranslations()
        addInit("defaultNS: 'common'")

        val diagnostics = ConfigDiagnostics.inspect(config(), { com.ibrahimdans.i18n.plugin.ide.settings.DirectoryState.OK }, namespaceFacts(project, config()))

        assertEquals(
            listOf(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_FROM_CODE, path = "common", origin = "src/i18n.ts")),
            diagnostics.filter { it.issue != ConfigIssue.ROOT_NOT_CONFIGURED }
        )
    }
}
