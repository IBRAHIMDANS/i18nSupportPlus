package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class I18nizeApplyTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(vararg files: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(files.toList()) }

    private fun prepare(plan: I18nizePlan, translations: Map<String, Map<String, String>> = emptyMap()): I18nizeApply =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan, translations) }

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    /** The text of the file at [path], or null when there is none. */
    private fun fileText(path: String): String? =
        myFixture.findFileInTempDir(path)?.let { ReadAction.compute<String, RuntimeException> { myFixture.psiManager.findFile(it)!!.text } }

    @Test
    fun everyFileIsWrittenInOneCommandThatOneUndoReverts() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val menu = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Home</p><p>Settings</p></nav>; }")
        val dialog = myFixture.addFileToProject("src/Dialog.tsx", "export function Dialog() { return <p>Close</p>; }")
        val menuBefore = textOf(menu)
        val dialogBefore = textOf(dialog)
        myFixture.configureFromExistingVirtualFile(menu.virtualFile)

        val outcome = prepare(planOf(menu, dialog)).write()

        Assertions.assertEquals(3, outcome.replaced)
        Assertions.assertEquals("""
            import { useTranslation } from 'react-i18next';
            export function Menu() { const { t } = useTranslation('common'); return <nav><p>{t('menu.home')}</p><p>{t('menu.settings')}</p></nav>; }
        """.trimIndent(), textOf(menu))
        Assertions.assertEquals("""
            import { useTranslation } from 'react-i18next';
            export function Dialog() { const { t } = useTranslation('common'); return <p>{t('dialog.close')}</p>; }
        """.trimIndent(), textOf(dialog))
        val written = textOf(translations)
        listOf("\"home\": \"Home\"", "\"settings\": \"Settings\"", "\"close\": \"Close\"").forEach {
            Assertions.assertTrue(it in written, written)
        }

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertEquals(menuBefore, textOf(menu))
        Assertions.assertEquals(dialogBefore, textOf(dialog))
        Assertions.assertEquals("{}", textOf(translations))
    }

    @Test
    fun aNamespaceWithoutFileGetsOneNextToTheOthers() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val file = myFixture.addFileToProject("src/Account.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Account() { const { t } = useTranslation('account'); return <p>Delete account</p>; }
        """.trimIndent())
        val plan = planOf(file).let { plan ->
            // Only the text: the hook's own 'account' is left to TASK-I18NIZE-FILTERS.
            I18nizePlan(plan.files.map { it.copy(entries = it.entries.filter { e -> e.candidate.originalText == "Delete account" }) })
        }

        val outcome = prepare(plan).write()

        Assertions.assertEquals(listOf("en/account.json"), outcome.createdFiles)
        val created = fileText("locales/en/account.json")!!
        Assertions.assertTrue("\"delete_account\": \"Delete account\"" in created, created)
        Assertions.assertTrue("{t('account:account.delete_account')}" in textOf(file), textOf(file))
    }

    @Test
    fun aNamespaceWithoutFileGetsOneWhereTheModuleTemplatePutsIt() = myFixture.runWithConfig(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "web", pathTemplate = "i18n/{ns}/{lang}.json")))
    ) {
        myFixture.addFileToProject("web/i18n/common/en.json", "{}")
        val file = myFixture.addFileToProject("web/Account.tsx", """
            import { useTranslation } from 'react-i18next';
            export function Account() { const { t } = useTranslation('account'); return <p>Delete account</p>; }
        """.trimIndent())
        val plan = planOf(file).let { plan ->
            I18nizePlan(plan.files.map { it.copy(entries = it.entries.filter { e -> e.candidate.originalText == "Delete account" }) })
        }

        val outcome = prepare(plan).write()

        Assertions.assertEquals(listOf("web/i18n/account/en.json"), outcome.createdFiles)
        val created = fileText("web/i18n/account/en.json")!!
        Assertions.assertTrue("\"delete_account\": \"Delete account\"" in created, created)
    }

    @Test
    fun theModuleCallTemplateWritesTheTextWhereItAsks() = myFixture.runWithConfig(
        config.copy(modules = listOf(ModuleConfig(
            name = "web", rootDirectory = "web", pathTemplate = "i18n/{ns}/{lang}.json", callTemplate = "translate({key}, {text})"
        )))
    ) {
        myFixture.addFileToProject("web/i18n/common/en.json", "{}")
        val file = myFixture.addFileToProject("web/Account.tsx", "export function Account() { return <p>Delete account</p>; }")

        prepare(planOf(file)).write()

        Assertions.assertTrue("{translate('account.delete_account', 'Delete account')}" in textOf(file), textOf(file))
    }

    @Test
    fun anExistingValueIsNeverReplaced() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", """{"menu": {"open": "Open the menu"}}""")
        val file = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Open</p></nav>; }")
        // A plan gone stale: the key it gives the text now holds another value.
        val plan = planOf(file).let { plan ->
            I18nizePlan(plan.files.map { it.copy(entries = it.entries.map { e -> e.copy(key = "menu.open") }) })
        }

        val outcome = prepare(plan).write()

        Assertions.assertEquals(1, outcome.keptValues)
        Assertions.assertEquals("""{"menu": {"open": "Open the menu"}}""", textOf(translations))
    }

    @Test
    fun theOtherLocalesGetTheValuesGivenAndTheirMissingFiles() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        myFixture.addFileToProject("locales/fr/common.json", "{}")
        val file = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <p>Home</p>; }")

        prepare(planOf(file), mapOf("fr" to mapOf("menu.home" to "Accueil"), "de" to mapOf("menu.home" to "Startseite"))).write()

        Assertions.assertTrue("\"home\": \"Home\"" in fileText("locales/en/common.json")!!)
        Assertions.assertTrue("\"home\": \"Accueil\"" in fileText("locales/fr/common.json")!!)
        Assertions.assertTrue("\"home\": \"Startseite\"" in fileText("locales/de/common.json")!!)
    }
}
