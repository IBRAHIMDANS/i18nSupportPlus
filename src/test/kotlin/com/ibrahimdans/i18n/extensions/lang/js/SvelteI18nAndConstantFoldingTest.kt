package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * svelte-i18n's `$_('key')` and a key passed through a constant (`t(KEYS.save)`) fold into their
 * value, like a literal `t('key')`. Neither was folded: the folding provider only looked at the
 * literal first argument of `t` and `$t`.
 *
 * Folds are read from the editor's folding manager where the question is whether the platform
 * folds them at all, and from the builder where the exact ranges matter.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class SvelteI18nAndConstantFoldingTest : PlatformBaseTest() {

    private val config = Config(
        defaultNs = "translation",
        foldingPreferredLanguage = "en",
        foldingMaxLength = 20,
        foldingEnabled = true,
    )

    private fun withTranslations(config: Config = this.config, block: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/translation.json", """{"menu":{"home":"Home","help":"Help"}}""")
        block()
    }

    /** The placeholders of the regions the folding manager built in the editor. */
    private fun foldedPlaceholders(): List<String> {
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
    }

    /** Each fold the builder makes, as the text it hides → the text it shows. */
    private fun builderFolds(): List<Pair<String, String>> {
        val text = myFixture.editor.document.text
        return ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
                .map { text.substring(it.range.startOffset, it.range.endOffset) to (it.placeholderText ?: "") }
        }
    }

    private fun svelte(call: String) = "<script>\n  import { _ } from 'svelte-i18n';\n</script>\n<p>{$call}</p>\n"

    // ── svelte-i18n ──────────────────────────────────────────────────────────────

    @Test
    fun `a svelte-i18n key in a Svelte component is folded`() = withTranslations {
        myFixture.configureByText("Page.svelte", svelte("\$_('menu.home')"))
        val placeholders = foldedPlaceholders()
        Assertions.assertTrue("Home" in placeholders, "the key folds into its value: $placeholders")
    }

    @Test
    fun `the other stores and a script are folded too`() = withTranslations {
        val code = "<script>\n  import { _, format } from 'svelte-i18n';\n  const title = \$format('menu.help');\n</script>\n<p>{\$_('menu.home')}</p>\n"
        myFixture.configureByText("Page.svelte", code)
        Assertions.assertEquals(
            listOf("\$format('menu.help')" to "Help", "\$_('menu.home')" to "Home"),
            builderFolds().sortedBy { code.indexOf(it.first) }
        )
    }

    @Test
    fun `a store called in a JavaScript file is folded`() = withTranslations {
        myFixture.configureByText("labels.js", "import { _, unwrapFunctionStore } from 'svelte-i18n';\nconst \$_ = unwrapFunctionStore(_);\nexport const home = \$_('menu.home');\n")
        Assertions.assertEquals(listOf("\$_('menu.home')" to "Home"), builderFolds())
    }

    /** The descriptor's `values` and `default` hold no key: the call folds once, on its `id`. */
    @Test
    fun `a descriptor folds on its id only`() = withTranslations {
        myFixture.configureByText("Page.svelte", svelte("\$_({ id: 'menu.home', values: { name: 'menu.help' }, default: 'menu.help' })"))
        val folds = builderFolds()
        Assertions.assertEquals(1, folds.size, "one fold for the call: $folds")
        Assertions.assertEquals("Home", folds.single().second)
    }

    @Test
    fun `a module whose preset is another framework does not fold a store`() = withTranslations(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        val file = myFixture.addFileToProject("apps/web/Page.svelte", svelte("\$_('menu.home')"))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        Assertions.assertEquals(emptyList<Pair<String, String>>(), builderFolds())
    }

    // ── Keys kept in constants ───────────────────────────────────────────────────

    @Test
    fun `a key passed through a constant object is folded`() = withTranslations {
        myFixture.configureByText(
            "Menu.ts",
            "const KEYS = { home: 'menu.home' } as const;\nexport const label = t(KEYS.home);\n"
        )
        val placeholders = foldedPlaceholders()
        Assertions.assertTrue("Home" in placeholders, "the call folds into its value: $placeholders")
        Assertions.assertEquals(listOf("t(KEYS.home)" to "Home"), builderFolds())
    }

    @Test
    fun `a constant and an enum member fold like a literal`() = withTranslations {
        myFixture.configureByText(
            "Menu.ts",
            "const HOME = 'menu.home';\nenum Keys { Help = 'menu.help' }\nexport const a = t(HOME);\nexport const b = t(Keys.Help);\n"
        )
        Assertions.assertEquals(listOf("t(HOME)" to "Home", "t(Keys.Help)" to "Help"), builderFolds())
    }

    @Test
    fun `a parameter or a reassigned variable is not folded`() = withTranslations {
        myFixture.configureByText(
            "Menu.ts",
            "export function label(key: string) { return t(key); }\nlet other = 'menu.home';\nother = 'menu.help';\nexport const b = t(other);\n"
        )
        Assertions.assertEquals(emptyList<Pair<String, String>>(), builderFolds())
    }
}
