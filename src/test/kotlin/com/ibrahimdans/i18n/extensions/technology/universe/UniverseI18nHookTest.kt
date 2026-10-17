package com.ibrahimdans.i18n.extensions.technology.universe

import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactUseTranslationHookExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.parser.RawKey
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The application's own hook in a universe:i18n project: `useTranslation('OverviewActions')` then
 * `t('help.caption')` reads `OverviewActions.help.caption`, a key prefix. It was read as
 * react-i18next's namespace, a file `OverviewActions.json` the project does not have.
 */
class UniverseI18nHookTest : PlatformBaseTest() {

    private val config = Config(flatKeys = true)

    private fun meteorPackages() = myFixture.addFileToProject(".meteor/packages", "meteor-base@1.5.1\nuniverse:i18n@2.0.0\n")

    private fun messages() = myFixture.addFileToProject("src/i18n/en.json", """{"OverviewActions.help.caption": "Help"}""")

    private fun component(hook: String, call: String, imports: String = "") = """
        $imports
        export function Overview() {
            $hook
            return $call;
        }
    """.trimIndent()

    /** The key the hook extractor reads at the caret. */
    private fun rawKeyAtCaret(): RawKey {
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val extractor = ReactUseTranslationHookExtractor()
        Assertions.assertTrue(extractor.canExtract(element), "the hook must be recognised")
        return extractor.extract(element)
    }

    @Test
    fun theApplicationsUseTranslationTakesAPrefix() = myFixture.runWithConfig(config) {
        meteorPackages()
        myFixture.configureByText("Overview.tsx", component("const { t } = useTranslation('OverviewActions');", "t('help.<caret>caption')"))
        val key = rawKeyAtCaret()
        Assertions.assertEquals(listOf("OverviewActions"), key.keyPrefix.map { it.text })
        Assertions.assertEquals(emptyList<String>(), key.arguments, "no namespace")
    }

    @Test
    fun useTranslatorAsTheDocumentationWritesItTakesAPrefix() = myFixture.runWithConfig(config) {
        meteorPackages()
        myFixture.configureByText("Overview.tsx", component("const t = useTranslator('OverviewActions');", "t('help.<caret>caption')"))
        Assertions.assertEquals(listOf("OverviewActions"), rawKeyAtCaret().keyPrefix.map { it.text })
    }

    @Test
    fun reactI18nextsHookKeepsItsNamespace() = myFixture.runWithConfig(config) {
        meteorPackages()
        myFixture.configureByText(
            "Overview.tsx",
            component("const { t } = useTranslation('OverviewActions');", "t('help.<caret>caption')", "import { useTranslation } from 'react-i18next';")
        )
        val key = rawKeyAtCaret()
        Assertions.assertEquals(listOf("OverviewActions"), key.arguments)
        Assertions.assertEquals(emptyList<String>(), key.keyPrefix.map { it.text })
    }

    @Test
    fun withoutUniverseI18nUseTranslationKeepsItsNamespace() = myFixture.runWithConfig(config) {
        myFixture.configureByText("Overview.tsx", component("const { t } = useTranslation('OverviewActions');", "t('help.<caret>caption')"))
        val key = rawKeyAtCaret()
        Assertions.assertEquals(listOf("OverviewActions"), key.arguments)
        Assertions.assertEquals(emptyList<String>(), key.keyPrefix.map { it.text })
    }

    @Test
    fun theFlatKeyUnderThePrefixResolves() = myFixture.runWithConfig(config) {
        meteorPackages()
        messages()
        myFixture.configureByText(
            "Overview.tsx",
            component(
                "const { t } = useTranslation('OverviewActions');",
                "[t('help.caption'), t('<error descr=\"Unresolved key\">help.missing</error>')]",
                // The application's own hook, as universe:i18n's documentation has it written.
                "function useTranslation(prefix: string) { return { t: (key: string) => prefix + '.' + key }; }"
            )
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
