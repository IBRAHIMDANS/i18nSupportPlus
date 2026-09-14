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
    fun `declared on every JavaScript dialect, each with a settings group`() {
        assertEquals(setOf("JavaScript", "JSX Harmony", "TypeScript", "TypeScript JSX"), ours.map { it.language }.toSet())
        ours.forEach { bean ->
            assertTrue(bean.isEnabledByDefault, "${bean.language}: on by default")
            // No message: with a String expected value the inherited junit.framework overload wins.
            assertEquals("OTHER_GROUP", bean.requiredGroup().name)
        }
    }

    @Test
    fun `the settings page lists the provider for TypeScript JSX`() {
        val tsx = Language.findLanguageByID("TypeScript JSX")!!
        val models = InlaySettingsProvider.EP.EXTENSION_POINT_NAME.extensionList.flatMap { provider: InlaySettingsProvider -> provider.createModels(project, tsx) }
        assertTrue(models.any { it.id == "i18n.translations.inlay" }, "listed among: ${models.map { it.id }}")
    }

    @Test
    fun `the highlighting pass renders the hint in a tsx file`() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        addFileToProject("locales/en/common.json", """{"title": "Deposit box"}""")
        myFixture.configureByText("Page.tsx", "const x = t('common:title');")
        myFixture.doHighlighting()
        val hints = ReadAction.compute<List<String>, RuntimeException> {
            myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.file.textLength)
                .mapNotNull { it.renderer as? DeclarativeInlayRenderer }
                .map { it.toString() }
        }
        assertTrue(hints.isNotEmpty(), "a resolved key gets its value rendered")
    }
}
