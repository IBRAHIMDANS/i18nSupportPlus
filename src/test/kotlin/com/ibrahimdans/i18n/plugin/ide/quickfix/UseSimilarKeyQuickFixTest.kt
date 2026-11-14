package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Use 'button.save'* on a key a typo away from one the translation file holds.
 *
 * Every assertion is qualified as `Assertions.…`: BasePlatformTestCase inherits JUnit 3's
 * `assertEquals(message, expected, actual)`, which would win over a static import.
 */
class UseSimilarKeyQuickFixTest : PlatformBaseTest() {

    private val create = "Create i18n key"

    private fun seed() = addFileToProject(
        "locales/en/common.json",
        """{"button": {"save": "Save", "saves": "Saves", "cancel": "Cancel"}, "title": "Title", "item_one": "1 item", "item_other": "items"}""",
    )

    private fun offered() = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Use '") || it == create }

    @Test
    fun `a typo is offered the key it misses, before Create key, and replaced by it`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('common:buton.sa<caret>ve');")

        Assertions.assertEquals(listOf("Use 'button.save'", "Use 'button.saves'", create), offered())

        myFixture.launchActionAndWait(myFixture.findSingleIntention("Use 'button.save'"))
        myFixture.checkResult("export const a = (t) => t('common:button.save');")
    }

    @Test
    fun `a key with no neighbour is offered no key`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('common:profile.ava<caret>tar');")

        Assertions.assertEquals(listOf(create), offered())
    }

    @Test
    fun `a plural is offered under the key the code writes`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('common:itme<caret>', { count: 2 });")

        myFixture.launchActionAndWait(myFixture.findSingleIntention("Use 'item'"))
        myFixture.checkResult("export const a = (t) => t('common:item', { count: 2 });")
    }

    @Test
    fun `the namespace of the hook stays unwritten`() {
        seed()
        myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export const A = () => { const { t } = useTranslation('common'); return <p>{t('titel<caret>')}</p>; };
            """.trimIndent(),
        )

        myFixture.launchActionAndWait(myFixture.findSingleIntention("Use 'title'"))
        myFixture.checkResult(
            """
            import { useTranslation } from 'react-i18next';
            export const A = () => { const { t } = useTranslation('common'); return <p>{t('title')}</p>; };
            """.trimIndent(),
        )
    }
}
