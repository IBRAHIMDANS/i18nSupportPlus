package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Use namespace 'common'* on a namespace a typo away from one a translation file holds.
 *
 * Every assertion is qualified as `Assertions.…`: BasePlatformTestCase inherits JUnit 3's
 * `assertEquals(message, expected, actual)`, which would win over a static import.
 */
class UseSimilarNamespaceQuickFixTest : PlatformBaseTest() {

    private fun seed() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save"}}""")
        addFileToProject("locales/en/auth.json", """{"login": "Log in"}""")
    }

    private fun offered() = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Use namespace '") }

    @Test
    fun `a typo in the namespace is offered the namespace it misses and replaced by it`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('com<caret>on:button.save');")

        Assertions.assertEquals(listOf("Use namespace 'common'"), offered())

        myFixture.launchActionAndWait(myFixture.findSingleIntention("Use namespace 'common'"))
        myFixture.checkResult("export const a = (t) => t('common:button.save');")
    }

    @Test
    fun `a namespace with no neighbour is offered no namespace`() {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('prof<caret>ile:avatar');")

        Assertions.assertEquals(emptyList<String>(), offered())
    }

    @Test
    fun `the project's namespace separator is kept`() = myFixture.runWithConfig(Config(nsSeparator = "~")) {
        seed()
        myFixture.configureByText("App.ts", "export const a = (t) => t('aut<caret>~login');")

        myFixture.launchActionAndWait(myFixture.findSingleIntention("Use namespace 'auth'"))
        myFixture.checkResult("export const a = (t) => t('auth~login');")
    }
}
