package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Template keys in flat translation files (#948): without interpolation, `` t(`a.b`) `` names its
 * key like `t('a.b')`; with one, `` t(`role.${role}.label`) `` names every entry the value can
 * complete it into, and is reported only when there is none.
 */
class StaticTemplateKeyTest : PlatformBaseTest() {

    private companion object {
        /** The `${role}` the fixtures write inside their template keys. */
        const val ROLE = "\${role}"
    }

    /** The text of each range reported as an unresolved key in [code]. */
    private fun unresolvedKeys(code: String): List<String> {
        var unresolved = emptyList<String>()
        myFixture.runWithConfig(Config(defaultNs = "translation", flatKeys = true)) {
            addFileToProject(
                "en/translation.json",
                """{"GenerateTour.locationHint.label":"Label","GenerateTour.locationHint.placeholder":"Placeholder","role.owner.label":"Owner"}"""
            )
            myFixture.configureByText("Tour.tsx", code.trimIndent())
            unresolved = myFixture.doHighlighting().filter { it.description == "Unresolved key" }.map { it.text }
        }
        return unresolved
    }

    @Test
    fun `a template key without interpolation resolves in a flat file`() {
        val code = """
            import { useTranslation } from 'react-i18next';
            export function Tour() {
                const { t } = useTranslation();
                return <input aria-label={t('GenerateTour.locationHint.label')} placeholder={t(`GenerateTour.locationHint.placeholder`)} />;
            }
        """
        assertEquals(emptyList<String>(), unresolvedKeys(code))
    }

    @Test
    fun `a template key with an interpolation resolves when an entry matches it`() {
        val code = """
            import { useTranslation } from 'react-i18next';
            export function Role({ role }: { role: string }) {
                const { t } = useTranslation();
                return <span>{t(`role.$ROLE.label`)}</span>;
            }
        """
        assertEquals(emptyList<String>(), unresolvedKeys(code))
    }

    @Test
    fun `a template key with an interpolation matching no entry is reported`() {
        val code = """
            import { useTranslation } from 'react-i18next';
            export function Role({ role }: { role: string }) {
                const { t } = useTranslation();
                return <span>{t(`status.$ROLE.label`)}</span>;
            }
        """
        assertEquals(1, unresolvedKeys(code).size)
    }
}
