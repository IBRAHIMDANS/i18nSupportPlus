package com.ibrahimdans.i18n.extensions.technology.typesafei18n

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.IdeWorkspace
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * typesafe-i18n as its generator lays it out: `src/i18n/<locale>/index.ts` default-exporting the
 * messages next to the generated `i18n-types.ts`, read in code as `LL.home.title()` — a member
 * chain, no string.
 */
class TypesafeI18nTest : PlatformBaseTest() {

    private val config = Config(previewLocale = "en", partialTranslationInspectionEnabled = true)

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val partial = PluginBundle.getMessage("annotator.partially.translated")

    private fun withProject(body: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("src/i18n/i18n-types.ts", "export type Locales = 'en' | 'fr'\n")
        addFileToProject(
            "src/i18n/en/index.ts",
            """
                import type { BaseTranslation } from '../i18n-types'
                const en = {
                    HI: 'Hello {name}!',
                    home: { title: 'Home', subtitle: 'Welcome' },
                } satisfies BaseTranslation
                export default en
            """.trimIndent()
        )
        addFileToProject(
            "src/i18n/fr/index.ts",
            """
                import type { Translation } from '../i18n-types'
                const fr = {
                    HI: 'Bonjour {name} !',
                    home: { title: 'Accueil' },
                } satisfies Translation
                export default fr
            """.trimIndent()
        )
        body()
    }

    private fun component(vararg reads: String) = """
        import { useI18nContext } from './i18n/i18n-react'
        export function App() {
            const { LL } = useI18nContext()
            return [${reads.joinToString()}]
        }
    """.trimIndent()

    /** The problems reported in the open file, as description → underlined text. */
    private fun problems(): Set<Pair<String, String>> {
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting().filter { it.description == unresolved || it.description == partial }
            .mapTo(mutableSetOf()) { it.description to text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun eachLocaleModuleIsASource() = withProject {
        val sources = ReadAction.compute<List<Pair<String?, String?>>, RuntimeException> {
            TypesafeI18nTechnology().findSourcesByConfiguration(IdeWorkspace(project)).map { it.locale to it.namespace }
        }
        assertEquals(setOf("en" to null, "fr" to null), sources.toSet())
    }

    @Test
    fun theOutputPathOfTheConfigIsRead() = myFixture.runWithConfig(config) {
        addFileToProject(".typesafe-i18n.json", """{ "adapter": "react", "outputPath": "./app/locales/" }""")
        addFileToProject("app/locales/de/index.ts", "const de = { title: 'Start' }\nexport default de\n")
        val locales = ReadAction.compute<List<String?>, RuntimeException> {
            TypesafeI18nTechnology().findSourcesByConfiguration(IdeWorkspace(project)).map { it.locale }
        }
        assertEquals(listOf("de"), locales)
    }

    @Test
    fun anUnknownKeyAndAMissingLocaleAreReported() = withProject {
        myFixture.configureByText("App.tsx", component("LL.HI({ name: 'x' })", "LL.home.title()", "LL.home.subtitle()", "LL.home.absent()"))

        assertEquals(setOf(unresolved to "absent", partial to "subtitle"), problems())
    }

    @Test
    fun aLevelInsideTheChainIsNoKeyOfItsOwn() = withProject {
        myFixture.configureByText("App.tsx", component("LL.home.title()"))

        assertTrue(problems().isEmpty(), "LL.home is the level of LL.home.title, not an object key: ${problems()}")
    }

    @Test
    fun theSvelteStoreIsReadToo() = withProject {
        myFixture.configureByText(
            "Nav.ts",
            "import LL from './i18n/i18n-svelte'\nexport const label = () => [\$LL.home.title(), \$LL.nope()]\n"
        )

        assertEquals(setOf(unresolved to "nope"), problems())
    }

    @Test
    fun anLLOfAnotherLibraryIsLeftAlone() = withProject {
        myFixture.configureByText("Other.ts", "import { LL } from './grammar'\nexport const x = LL.absent.rule()\n")

        assertTrue(problems().isEmpty(), problems().toString())
    }

    /** The value in the preview locale shows next to each message: what the generated types cannot offer. */
    @Test
    fun completionOffersTheMembersOfEachLevelWithTheirValue() = withProject {
        assertEquals(mapOf("HI" to "Hello {name}!", "home" to null), typeTexts(component("LL.<caret>"), "HI", "home"))
        assertEquals(mapOf("title" to "Home", "subtitle" to "Welcome"), typeTexts(component("LL.home.<caret>"), "title", "subtitle"))
    }

    /** The type text of each of [names] offered by completion in [text], null for a level. */
    private fun typeTexts(text: String, vararg names: String): Map<String, String?> {
        myFixture.configureByText("App.tsx", text)
        myFixture.complete(CompletionType.BASIC, 1)
        val lookups = myFixture.lookupElements.orEmpty()
        return names.associateWith { name ->
            val lookup = lookups.firstOrNull { it.lookupString == name } ?: error("$name is not offered: ${lookups.map { it.lookupString }}")
            LookupElementPresentation().also(lookup::renderElement).typeText
        }
    }
}
