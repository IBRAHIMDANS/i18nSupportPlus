package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A template literal without interpolation names its key like a quoted string: with flat keys,
 * `` t(`a.b`) `` used to be reported unresolved while `t('a.b')` resolved (#948).
 */
class StaticTemplateKeyTest : PlatformBaseTest() {

    @Test
    fun `a template key without interpolation resolves in a flat file`() {
        myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
            addFileToProject(
                "en/translation.json",
                """{"GenerateTour.locationHint.label":"Label","GenerateTour.locationHint.placeholder":"Placeholder"}"""
            )
            myFixture.configureByText(
                "Tour.tsx",
                """
                import { useTranslation } from 'react-i18next';
                export function Tour() {
                    const { t } = useTranslation();
                    return <input aria-label={t('GenerateTour.locationHint.label')} placeholder={t(`GenerateTour.locationHint.placeholder`)} />;
                }
                """.trimIndent()
            )
            val unresolved = myFixture.doHighlighting().filter { it.description == "Unresolved key" }.map { it.text }
            assertEquals(emptyList<String>(), unresolved)
        }
    }
}
