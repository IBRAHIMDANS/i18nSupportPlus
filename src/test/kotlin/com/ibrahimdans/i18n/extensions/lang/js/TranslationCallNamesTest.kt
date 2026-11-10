package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.TemplateKeyExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A template key and a folded key are read under every name the technologies publish, as the
 * annotator reads a literal key: `TemplateKeyExtractor` only knew `t`, `$t` and `i18n.t`, and the
 * folding provider `t` and `$t`, so `` $tc(`a.${x}`) `` was never checked and `i18n._('key')` never
 * folded. A function no framework publishes, or that the module's preset leaves out, stays out.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationCallNamesTest : PlatformBaseTest() {

    private companion object {
        /** The `${x}` the fixtures write inside their template keys. */
        const val X = "\${x}"
    }

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

    /** Whether the template key of the open file is read as a key. */
    private fun templateIsKey(): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val template = PsiTreeUtil.findChildOfType(myFixture.file, JSStringTemplateExpression::class.java)
        Assertions.assertNotNull(template, "the fixture holds a template")
        TemplateKeyExtractor().canExtract(template!!)
    }

    /** The text of each range the annotator reports as an unresolved key in the open file. */
    private fun unresolvedKeys(): List<String> {
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description?.startsWith("Unresolved key") == true }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    /** Each fold the builder makes, as the text it hides → the text it shows. */
    private fun builderFolds(): List<Pair<String, String>> {
        val text = myFixture.editor.document.text
        return ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
                .map { text.substring(it.range.startOffset, it.range.endOffset) to (it.placeholderText ?: "") }
        }
    }

    // ── Template keys ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["t", "\$t", "i18n.t", "this.\$t", "\$tc", "i18next.t", "props.t", "i18n._"])
    fun `a template key is read under a published name`(call: String) = withTranslations {
        myFixture.configureByText("labels.js", "export const label = $call(`menu.$X`);\n")
        Assertions.assertTrue(templateIsKey(), "$call(`menu.$X`) holds a key")
    }

    @ParameterizedTest
    @ValueSource(strings = ["format", "toast.t", "router.get"])
    fun `a template passed to another function is no key`(call: String) = withTranslations {
        myFixture.configureByText("labels.js", "export const label = $call(`menu.$X`);\n")
        Assertions.assertFalse(templateIsKey(), "$call(`menu.$X`) holds no key")
    }

    @Test
    fun `a template key under a published name is checked like a literal one`() = withTranslations {
        myFixture.configureByText(
            "labels.js",
            "export const a = \$tc(`menu.$X`);\nexport const b = \$tc(`missing.$X`);\n"
        )
        // The range covers the key, not its backquotes.
        Assertions.assertEquals(listOf("missing.$X"), unresolvedKeys())
    }

    @Test
    fun `a template key under a name the module's preset leaves out is no key`() = withTranslations(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        val file = myFixture.addFileToProject("apps/web/labels.js", "export const label = \$tc(`menu.$X`);\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        Assertions.assertFalse(templateIsKey(), "vue-i18n's \$tc is not i18next's")
    }

    // ── Folding ──────────────────────────────────────────────────────────────────

    @Test
    fun `a key under any published name is folded`() = withTranslations {
        myFixture.configureByText(
            "labels.js",
            "export const a = t('menu.home');\nexport const b = \$tc('menu.help');\nexport const c = i18n._('menu.home');\nexport const d = i18next.t('menu.help');\n"
        )
        Assertions.assertEquals(
            listOf("t('menu.home')" to "Home", "\$tc('menu.help')" to "Help", "i18n._('menu.home')" to "Home", "i18next.t('menu.help')" to "Help"),
            builderFolds()
        )
    }

    @Test
    fun `an import-gated name is folded only where the file imports its framework`() = withTranslations {
        myFixture.configureByText("plain.js", "export const a = msg('menu.home');\n")
        Assertions.assertEquals(emptyList<Pair<String, String>>(), builderFolds(), "a local msg() is no Lingui call")

        myFixture.configureByText("lingui.js", "import { msg } from '@lingui/core/macro';\nexport const a = msg('menu.home');\n")
        Assertions.assertEquals(listOf("msg('menu.home')" to "Home"), builderFolds())
    }

    @Test
    fun `a function no framework publishes is not folded`() = withTranslations {
        myFixture.configureByText("labels.js", "export const a = format('menu.home');\nexport const b = toast.t('menu.help');\n")
        Assertions.assertEquals(emptyList<Pair<String, String>>(), builderFolds())
    }

    @Test
    fun `a module's preset keeps its own names and the bare t`() = withTranslations(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        val file = myFixture.addFileToProject("apps/web/labels.js", "export const a = \$tc('menu.home');\nexport const b = t('menu.help');\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        Assertions.assertEquals(listOf("t('menu.help')" to "Help"), builderFolds())
    }
}
