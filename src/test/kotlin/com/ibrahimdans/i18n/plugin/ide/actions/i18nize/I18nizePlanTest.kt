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

        val entry = planOf(file).files.single().entries.single()
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

    private fun I18nizePlan.texts(): List<String> = files.flatMap { file -> file.entries.map { it.candidate.originalText } }

    @Test
    fun aHookOrTranslationCallArgumentIsNeverPlanned() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/Account.tsx", """
            'use client';
            import { useTranslation } from 'react-i18next';
            import { useTranslations, getTranslations } from 'next-intl';
            export function Account() {
                const { t } = useTranslation('account');
                const many = useTranslation(['account', 'common']);
                const prefixed = useTranslation('account', { keyPrefix: 'form' });
                const next = useTranslations('Account');
                const fixed = i18n.getFixedT('fr', 'account');
                const label = t('title', { ns: 'account' });
                return <p>Delete account</p>;
            }
            export async function Page() { const t = await getTranslations('Page'); return null; }
        """.trimIndent())

        Assertions.assertEquals(listOf("Delete account"), planOf(file).texts())
    }

    @Test
    fun aTechnicalAttributeIsNeverPlannedButAnAriaLabelIs() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/Menu.tsx", """
            export const Menu = () => <nav className={'menu'} id={'main-menu'} data-testid={'menu'} aria-labelledby={'title'}>
                <a href={'/home'} target={'_blank'} rel={'noopener'} aria-label={'Go home'} onClick={() => alert('Opened')}>Home</a>
                <input type={'text'} name={'q'} aria-description={'Search the site'} />
            </nav>;
        """.trimIndent())

        Assertions.assertEquals(listOf("Go home", "Opened", "Home", "Search the site"), planOf(file).texts())
    }

    @Test
    fun dataIsPlannedUncheckedWithItsReasonAndAMessageChecked() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/Links.tsx", """
            export function Links() {
                const site = 'https://example.com';
                const mode = 'darkMode';
                const date = '2024-01-31';
                return <div><button>OK</button><p>Save</p><span>42</span></div>;
            }
        """.trimIndent())

        val entries = planOf(file).files.single().entries.associate { it.candidate.originalText to it.uncheckedReason }
        Assertions.assertEquals(
            mapOf(
                "https://example.com" to TranslatableText.Reason.URL,
                "darkMode" to TranslatableText.Reason.IDENTIFIER,
                "2024-01-31" to TranslatableText.Reason.DATE,
                "OK" to null,
                "Save" to null,
            ),
            entries
        )
        Assertions.assertEquals(listOf(false, false, false, true, true), planOf(file).files.single().entries.map { it.checkedByDefault })
    }

    /** react-intl: a string outside any component has no `intl` to call and none to receive. */
    @Test
    fun aReactIntlStringOutsideAnyComponentIsRefused() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/labels.ts", """
            import { defineMessages } from 'react-intl';
            export function label() { return 'Save changes'; }
        """.trimIndent())

        val entry = planOf(file).files.single().entries.single()

        Assertions.assertNotNull(entry.refusal, "$entry")
        Assertions.assertFalse(entry.checkedByDefault)
        val model = I18nizePreviewModel(planOf(file), emptyMap(), config)
        model.rows.forEach { it.selected = true }
        Assertions.assertTrue(model.accepted().files.isEmpty(), "a refused text, even checked, is never applied")
    }

    /** A component without `useIntl()` receives one, so its string is no refusal. */
    @Test
    fun aReactIntlStringOfAComponentIsPlanned() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/Form.tsx", """
            import { defineMessages } from 'react-intl';
            export function Form() { return <input placeholder="Your name" />; }
        """.trimIndent())

        val entry = planOf(file).files.single().entries.single()

        Assertions.assertNull(entry.refusal, "$entry")
        Assertions.assertTrue(entry.checkedByDefault)
    }

    @Test
    fun anI18nextStringOutsideAnyComponentIsNoRefusal() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", "{}")
        val file = addFileToProject("src/labels.ts", "export function label() { return 'Save changes'; }")

        Assertions.assertNull(planOf(file).files.single().entries.single().refusal)
    }
}
