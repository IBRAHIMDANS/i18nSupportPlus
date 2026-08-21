package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
import org.junit.jupiter.api.Test

/**
 * End-to-end highlighting for a translation catalog written as a plain TypeScript object
 * keyed by locale — the React Native / Expo layout.
 *
 * Before [com.ibrahimdans.i18n.extensions.technology.tscatalog.TsCatalogTechnology] no code
 * path opened such a file: TsLocalization declares no file type, and the i18next technology
 * only reads a `resources` object. `findSources` therefore came back empty and every key of
 * the project was flagged "Missing default translation file" — the plugin looked broken.
 */
class TsCatalogHighlightingTest : PlatformBaseTest() {

    private val cg = TsCodeGenerator()

    private val catalog = """
        export const translations = {
          fr: { common: { cancel: 'Annuler' }, dashboard: { title: 'Ma pharmacie' } },
          en: { common: { cancel: 'Cancel' }, dashboard: { title: 'My pharmacy' } },
        } as const;
    """.trimIndent()

    @Test
    fun keyOfAPlainTsCatalogResolves() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"dashboard.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * The error starts at the first segment that does not resolve, the resolved prefix stays
     * outside it — the convention every other highlighting test follows (see
     * `CodeHighlightingTestBase`, `tst1.<error>unresolved.part.of.key</error>`). Here
     * `dashboard` resolves against the catalog and only `subtitle` is missing.
     */
    @Test
    fun missingKeyOfAPlainTsCatalogIsReported() = myFixture.runWithConfig(Config()) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"dashboard.<error descr=\"Unresolved key\">subtitle</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * The regression this task exists for: with no catalog in the project the key is not
     * merely unresolved, the plugin reports it has no translation file at all.
     */
    @Test
    fun keyIsUnresolvableWhenNoCatalogExists() = myFixture.runWithConfig(Config()) {
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.generate("\"<error descr=\"Missing default translation file\">dashboard.title</error>\"")
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    /**
     * Anti-regression for projects that keep JSON namespaces: the new technology contributes
     * sources, it must not take any away.
     */
    @Test
    fun jsonNamespacesKeepResolving() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", """{"app": {"header": {"title": "Titre"}}}""")
        myFixture.configureByText("test.${cg.ext()}", cg.generate("\"app.header.title\""))
        myFixture.checkHighlighting(true, true, true, true)
    }
}
