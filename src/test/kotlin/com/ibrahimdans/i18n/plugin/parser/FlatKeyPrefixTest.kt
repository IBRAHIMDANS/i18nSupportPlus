package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Test

/**
 * A hook's key prefix in a project storing flat keys: `t('help.caption')` under
 * `useTranslations('OverviewActions')` names the property `OverviewActions.help.caption` of a flat
 * file. The prefix was a segment of its own, so the key was looked up nested in an
 * `OverviewActions` object and reported as unresolved.
 */
class FlatKeyPrefixTest : PlatformBaseTest() {

    private val config = Config(flatKeys = true)

    private fun addMessages() =
        myFixture.addFileToProject("messages/en.json", """{"OverviewActions.help.caption": "Help", "Shared.ok": "OK"}""")

    @Test
    fun nextIntlNamespaceIsJoinedToTheFlatKey() = myFixture.runWithConfig(config) {
        addMessages()
        myFixture.configureByText(
            "Overview.tsx",
            """
                import { useTranslations } from 'next-intl';
                export function Overview() {
                    const t = useTranslations('OverviewActions');
                    return t('help.caption');
                }
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun keyPrefixOptionIsJoinedToTheFlatKey() = myFixture.runWithConfig(config) {
        addMessages()
        myFixture.configureByText(
            "Overview.tsx",
            """
                import { useTranslation } from 'react-i18next';
                export function Overview() {
                    const { t } = useTranslation(undefined, { keyPrefix: 'OverviewActions' });
                    return t('help.caption');
                }
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, true, true, true)
    }

    @Test
    fun aKeyMissingUnderThePrefixIsStillReported() = myFixture.runWithConfig(config) {
        addMessages()
        myFixture.configureByText(
            "Overview.tsx",
            """
                import { useTranslations } from 'next-intl';
                export function Overview() {
                    const t = useTranslations('OverviewActions');
                    return t('<error descr="Unresolved key">ok</error>');
                }
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, true, true, true)
    }
}
