package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Internationalize…* on a Svelte component: the texts of its markup become calls of svelte-i18n's
 * store, imported once when the component does not import it yet.
 */
class I18nizeSvelteTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(vararg files: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(files.toList()) }

    private fun write(plan: I18nizePlan): I18nizeApply.Outcome =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan) }.write("Internationalize")

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    @Test
    fun aComponentIsAccepted() {
        val component = addFileToProject("src/App.svelte", "<h1>Welcome</h1>")
        Assertions.assertTrue(I18nizeAction.isApplicable(arrayOf(component.virtualFile)))
    }

    @Test
    fun theTextsOfAComponentBecomeStoreCallsAndTheStoreIsImported() = myFixture.runWithConfig(config) {
        val translations = addFileToProject("locales/en/common.json", "{}")
        val component = addFileToProject(
            "src/Profile.svelte",
            "<script>\n\tlet user = { name: 'x' };\n</script>\n\n" +
                "<h1>Welcome</h1><p>Hello {user.name}!</p><input placeholder=\"Your name\">"
        )
        myFixture.configureFromExistingVirtualFile(component.virtualFile)

        val plan = planOf(component)
        val entries = plan.files.single().entries
        Assertions.assertEquals(listOf("Welcome", "Hello {name}!", "Your name"), entries.map { it.candidate.originalText })
        write(plan)

        val (welcome, hello, name) = entries.map { it.key }
        Assertions.assertEquals(
            "<script>\n\timport { _ } from 'svelte-i18n';\n\tlet user = { name: 'x' };\n</script>\n\n" +
                "<h1>{\$_('$welcome')}</h1><p>{\$_('$hello', { values: { name: user.name } })}</p>" +
                "<input placeholder={\$_('$name')}>",
            textOf(component)
        )
        Assertions.assertTrue("\"Hello {name}!\"" in textOf(translations), textOf(translations))
    }
}
