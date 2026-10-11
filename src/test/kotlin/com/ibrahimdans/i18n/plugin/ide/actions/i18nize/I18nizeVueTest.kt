package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Internationalize…* on a Vue component: the texts of its `<template>` become `$t` calls — no
 * import, no hook — written with their values in one command.
 */
class I18nizeVueTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun planOf(vararg files: PsiFile): I18nizePlan =
        ReadAction.compute<I18nizePlan, RuntimeException> { I18nizePlan.of(files.toList()) }

    private fun write(plan: I18nizePlan): I18nizeApply.Outcome =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan) }.write("Internationalize")

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun I18nizePlan.texts(): List<String> = files.flatMap { file -> file.entries.map { it.candidate.originalText } }

    private fun component(template: String, script: String = "") =
        myFixture.addFileToProject("src/Profile.vue", "<template>\n$template\n</template>\n$script")

    @Test
    fun aTextAndAnAttributeBecomeCallsInOneUndo() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("""<div><h1>Welcome</h1><input placeholder="Your name"></div>""")
        val before = textOf(profile)
        myFixture.configureFromExistingVirtualFile(profile.virtualFile)

        val plan = planOf(profile)
        Assertions.assertEquals(listOf("profile.welcome", "profile.your_name"), plan.files.single().entries.map { it.key })
        val outcome = write(plan)

        Assertions.assertEquals(2, outcome.replaced)
        Assertions.assertEquals(
            "<template>\n<div><h1>{{ \$t('profile.welcome') }}</h1><input :placeholder=\"\$t('profile.your_name')\"></div>\n</template>\n",
            textOf(profile)
        )
        listOf("\"welcome\": \"Welcome\"", "\"your_name\": \"Your name\"").forEach {
            Assertions.assertTrue(it in textOf(translations), textOf(translations))
        }

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor!!)

        Assertions.assertEquals(before, textOf(profile))
        Assertions.assertEquals("{}", textOf(translations))
    }

    @Test
    fun anInterpolatedValueBecomesAVariable() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("<p>\n  Hello {{ user.name }}!\n</p>")

        val plan = planOf(profile)
        val entry = plan.files.single().entries.single()
        Assertions.assertEquals("Hello {name}!", entry.candidate.originalText)
        write(plan)

        Assertions.assertEquals(
            "<template>\n<p>\n  {{ \$t('${entry.key}', { name: user.name }) }}\n</p>\n</template>\n",
            textOf(profile)
        )
        Assertions.assertTrue("\"Hello {name}!\"" in textOf(translations), textOf(translations))
    }

    @Test
    fun whatIsNotATemplateMessageIsNotProposed() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component(
            """<div><p>{{ ${'$'}t('done') }}</p><p>Born {{ format(date) }}</p><code>npm install</code><a href="/home" title="Home">Go</a></div>""",
            "<script setup>\nconst label = 'Not from the template'\n</script>\n<style>.a { content: 'x' }</style>"
        )

        Assertions.assertEquals(listOf("Home", "Go"), planOf(profile).texts())
    }

    @Test
    fun aFolderSelectionReadsVueComponents() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val profile = component("<p>Welcome</p>")
        myFixture.addFileToProject("src/Header.tsx", "export function Header() { return <h1>Title</h1>; }")

        val preview = ReadAction.compute<I18nizeAction.Preview, RuntimeException> {
            I18nizeAction.compute(project, listOf(profile.virtualFile.parent), EmptyProgressIndicator())
        }

        Assertions.assertEquals(listOf("Header.tsx", "Profile.vue"), preview.plan.files.map { it.file.name })
        Assertions.assertTrue(I18nizeAction.isApplicable(arrayOf(profile.virtualFile)))
    }
}
