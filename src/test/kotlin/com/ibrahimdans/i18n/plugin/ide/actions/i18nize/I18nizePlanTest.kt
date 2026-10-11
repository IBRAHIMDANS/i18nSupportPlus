package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class I18nizePlanTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(vararg files: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(files.toList()) }

    private fun I18nizePlan.keys(): List<Triple<String, String, Boolean>> =
        files.flatMap { file -> file.entries.map { Triple(it.candidate.originalText, it.key, it.reused) } }

    @Test
    fun eachTextGetsAKeyNamedByItsComponent() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/Profile.tsx", """
            export function UserProfile() { return <div><h1>My profile</h1><button>Save changes</button></div>; }
        """.trimIndent())

        Assertions.assertEquals(
            listOf(Triple("My profile", "userProfile.my_profile", false), Triple("Save changes", "userProfile.save_changes", false)),
            planOf(file).keys()
        )
    }

    @Test
    fun theHookGivesItsNamespace() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        addFileToProject("locales/en/account.json", "{}")
        val file = addFileToProject("src/Account.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Account() { const { t } = useTranslation('account'); return <p>Delete account</p>; }
        """.trimIndent())

        // The hook's own 'account' is a candidate too, as Batch Extract finds it: left to TASK-I18NIZE-FILTERS.
        val entry = planOf(file).files.single().entries.single { it.candidate.originalText == "Delete account" }
        Assertions.assertEquals("account", entry.namespace)
        Assertions.assertEquals("account:account.delete_account", entry.key)
    }

    @Test
    fun aTextAlreadyTranslatedReusesItsKey() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        val file = addFileToProject("src/Menu.tsx", "export const Menu = () => <nav><p>Save</p><p>Open</p></nav>;")

        Assertions.assertEquals(listOf(Triple("Save", "actions.save", true), Triple("Open", "menu.open", false)), planOf(file).keys())
    }

    @Test
    fun anExistingKeyOfAnotherValueIsNotTaken() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"open": "Open the menu"}}""")
        val file = addFileToProject("src/Menu.tsx", "export const Menu = () => <nav><p>Open</p></nav>;")

        Assertions.assertEquals(listOf(Triple("Open", "menu.open_2", false)), planOf(file).keys())
    }

    @Test
    fun theSameTextInTwoFilesComponentsIsTwoKeysAndOnceInOneComponent() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val menu = addFileToProject("src/Menu.tsx", "export const Menu = () => <nav><p>Close</p><span>Close</span></nav>;")
        val dialog = addFileToProject("src/Dialog.tsx", "export const Dialog = () => <div><p>Close</p></div>;")

        val keys = planOf(menu, dialog).keys().map { it.second }
        Assertions.assertEquals(listOf("menu.close", "menu.close", "dialog.close"), keys)
    }

    @Test
    fun aFileWithoutTextIsLeftOut() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val empty = addFileToProject("src/Empty.tsx", "export const Empty = () => <div>{props.children}</div>;")
        val menu = addFileToProject("src/Menu.tsx", "export const Menu = () => <p>Home</p>;")

        val plan = planOf(empty, menu)
        Assertions.assertEquals(listOf(menu), plan.files.map { it.file })
        Assertions.assertTrue(planOf(empty).files.isEmpty())
    }
}
