package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.codeInsight.hints.settings.InlaySettingsProvider
import com.intellij.lang.Language
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The provider as the platform sees it — its declaration, not its collector, which
 * [I18nInlayHintsProviderTest] drives directly.
 *
 * Without a `group` attribute the declaration builds a bean whose `requiredGroup()` fails
 * `checkNotNull`: the settings page dropped the provider — *i18n translations* appeared
 * nowhere under *Editor > Inlay Hints* — and nothing could switch it on or off.
 */
class I18nInlayHintsRegistrationTest : PlatformBaseTest() {

    private val ours: List<InlayHintsProviderExtensionBean>
        get() = InlayHintsProviderExtensionBean.EP.extensionList.filter { it.providerId == "i18n.translations.inlay" }

    @Test
    fun `declared on JavaScript alone, with a settings group`() {
        // The dialects inherit it: one declaration per dialect rendered a .tsx hint three times.
        assertEquals(listOf("JavaScript"), ours.map { it.language })
        ours.forEach { bean ->
            assertTrue(bean.isEnabledByDefault, "${bean.language}: on by default")
            // No message: with a String expected value the inherited junit.framework overload wins.
            assertEquals("OTHER_GROUP", bean.requiredGroup().name)
        }
    }

    @Test
    fun `the settings page lists the provider for JavaScript`() {
        val js = Language.findLanguageByID("JavaScript")!!
        val models = InlaySettingsProvider.EP.EXTENSION_POINT_NAME.extensionList.flatMap { provider: InlaySettingsProvider -> provider.createModels(project, js) }
        assertTrue(models.any { it.id == "i18n.translations.inlay" }, "listed among: ${models.map { it.id }}")
    }

    private fun renderedHints(file: String, code: String): List<String> {
        myFixture.configureByText(file, code)
        myFixture.doHighlighting()
        return ReadAction.compute<List<String>, RuntimeException> {
            myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.file.textLength)
                .mapNotNull { it.renderer as? DeclarativeInlayRenderer }
                .map { it.toString() }
        }
    }

    @Test
    fun `the highlighting pass renders the hint once in a tsx file`() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        addFileToProject("locales/en/common.json", """{"title": "Deposit box"}""")
        val hints = renderedHints("Page.tsx", "const x = t('common:title');")
        assertEquals(1, hints.size, "one hint, not one per dialect the file inherits: $hints")
    }

    @Test
    fun `a plural key gets the hint of its first form`() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        val hints = renderedHints("Plural.tsx", "const x = t('common:item', { count: 2 });")
        assertEquals(1, hints.size, "a key held as item_one / item_other is resolved: $hints")
    }
}
