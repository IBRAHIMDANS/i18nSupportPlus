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
 * *Internationalize…* on an Angular template: its texts become `| translate` pipes, and a `.html`
 * file Angular does not parse holds none.
 */
class I18nizeAngularTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(vararg files: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(files.toList()) }

    private fun write(plan: I18nizePlan): I18nizeApply.Outcome =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan) }.write("Internationalize")

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun template(markup: String): PsiFile {
        addFileToProject("package.json", """{"dependencies": {"@angular/core": "17.0.0", "@ngx-translate/core": "15.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "src/app/profile.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-profile', templateUrl: './profile.component.html' })\nexport class ProfileComponent {}"
        )
        return addFileToProject("src/app/profile.component.html", markup)
    }

    @Test
    fun theTextsOfATemplateBecomePipes() = myFixture.runWithConfig(config) {
        val translations = addFileToProject("locales/en/common.json", "{}")
        val profile = template("""<h1>Welcome</h1><p>Hello {{ user.name }}!</p><input placeholder="Your name">""")
        myFixture.configureFromExistingVirtualFile(profile.virtualFile)

        val plan = planOf(profile)
        val entries = plan.files.single().entries
        Assertions.assertEquals(listOf("Welcome", "Hello {{name}}!", "Your name"), entries.map { it.candidate.originalText })
        write(plan)

        val (welcome, hello, name) = entries.map { it.key }
        Assertions.assertEquals(
            "<h1>{{ '$welcome' | translate }}</h1><p>{{ '$hello' | translate: { name: user.name } }}</p>" +
                "<input [placeholder]=\"'$name' | translate\">",
            textOf(profile)
        )
        Assertions.assertTrue("\"Hello {{name}}!\"" in textOf(translations), textOf(translations))
    }

    @Test
    fun aPlainHtmlFileHoldsNoText() = myFixture.runWithConfig(config) {
        val page = addFileToProject("public/index.html", "<button>Save</button>")
        Assertions.assertEquals(emptyList<String>(), ReadAction.compute<List<String>, RuntimeException> {
            I18nizeSources.candidates(page).map { it.originalText }
        })
    }
}
