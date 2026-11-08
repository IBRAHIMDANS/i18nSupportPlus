package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The editor features of the *Supported Languages* table that no test covered in Svelte
 * components, Angular templates and Astro components: completion, inlay hints and references in
 * Svelte, folding and inlay hints in an Angular template, inlay hints in Astro.
 *
 * Each goes through the platform's own pass — the daemon for hints, the folding manager for
 * folds — not through our provider called by hand: what was untested is precisely whether the
 * platform asks our extensions in these files at all.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TemplateEditorFeaturesTest : PlatformBaseTest() {

    private val config = Config(
        defaultNs = "translation",
        foldingPreferredLanguage = "en",
        foldingMaxLength = 20,
        foldingEnabled = true,
    )

    private fun withTranslations(block: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/translation.json", """{"menu":{"home":"Home","help":"Help"}}""")
        block()
    }

    private fun open(fileName: String, text: String, fileType: String) {
        myFixture.configureByText(fileName, text)
        // "Nothing happened" is also what a file nobody parsed shows: without this a negative
        // result would only say that the language plugin is missing.
        Assertions.assertEquals(fileType, myFixture.file.fileType.name, "$fileName must be parsed as $fileType")
    }

    /** The hints the daemon rendered in the editor, as their text. */
    private fun renderedHints(): List<String> {
        myFixture.doHighlighting()
        return ReadAction.compute<List<String>, RuntimeException> {
            myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
                .mapNotNull { it.renderer as? DeclarativeInlayRenderer }
                .map { it.toString() }
        }
    }

    /** The placeholders of the regions the folding manager built in the editor. */
    private fun foldedPlaceholders(): List<String> {
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
    }

    private fun resolvedValue(key: String): String? = ReadAction.compute<String?, RuntimeException> {
        val element = myFixture.elementAt(myFixture.file, key)
        Assertions.assertNotNull(element, "no PSI element carries the key")
        element!!.references.firstNotNullOfOrNull { it.resolve() }?.text?.unQuote()
    }

    // ── Svelte ───────────────────────────────────────────────────────────────────

    private fun svelte(call: String) = "<script>\n  import { _ } from 'svelte-i18n';\n</script>\n<p>{$call}</p>\n"

    @Test
    fun `a key in a Svelte component is completed`() = withTranslations {
        open("Completion.svelte", svelte("\$_('menu.ho<caret>')"), "Svelte")
        myFixture.complete(CompletionType.BASIC, 1)
        myFixture.checkResult(svelte("\$_('menu.home')"))
    }

    @Test
    fun `a key in a Svelte component gets an inlay hint`() = withTranslations {
        open("Hint.svelte", svelte("\$_('menu.home')"), "Svelte")
        val hints = renderedHints()
        Assertions.assertEquals(1, hints.size, "one hint for the key: $hints")
    }

    /**
     * Already ✓ in the table, on `JsFoldingBuilder` called by hand: here through the editor's own
     * pass, which ran no builder of ours on a `.svelte` before `svelteConfig.xml`. svelte-i18n's
     * `$_('key')` is covered by `SvelteI18nAndConstantFoldingTest`.
     */
    @Test
    fun `a Paraglide message in a Svelte component is folded`() = myFixture.runWithConfig(config) {
        addFileToProject("messages/en.json", """{"hello": "Hello"}""")
        open("Paraglide.svelte", "<script>\n  import { m } from '\$lib/paraglide/messages.js';\n</script>\n<p>{m.hello()}</p>\n", "Svelte")
        val placeholders = foldedPlaceholders()
        Assertions.assertTrue("Hello" in placeholders, "the message folds into its value: $placeholders")
    }

    @Test
    fun `a key in a Svelte component resolves`() = withTranslations {
        open("Reference.svelte", svelte("\$_('menu.home')"), "Svelte")
        Assertions.assertEquals("Home", resolvedValue("menu.home"))
    }

    // ── Angular templates ────────────────────────────────────────────────────────

    private fun angularProject() {
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@ngx-translate/core":"15.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './tpl.html' })
            export class AppComponent {}
            """.trimIndent()
        )
    }

    @Test
    fun `a key in an Angular template is folded`() = withTranslations {
        angularProject()
        open("tpl.html", """<p>{{ 'menu.home' | translate }}</p>""", "Angular17Html")
        val placeholders = foldedPlaceholders()
        Assertions.assertTrue("Home" in placeholders, "the key folds into its value: $placeholders")
    }

    @Test
    fun `a key in an Angular template gets an inlay hint`() = withTranslations {
        angularProject()
        open("tpl.html", """<p>{{ 'menu.home' | translate }}</p>""", "Angular17Html")
        val hints = renderedHints()
        Assertions.assertEquals(1, hints.size, "one hint for the key: $hints")
    }

    // ── Astro ────────────────────────────────────────────────────────────────────

    @Test
    fun `a key in an Astro component gets an inlay hint`() = withTranslations {
        open(
            "Hint.astro",
            "---\nimport { t } from 'i18next';\nconst title = t('menu.home');\n---\n<h1>{title}</h1>\n<p>{t('menu.help')}</p>\n",
            "Astro"
        )
        val hints = renderedHints()
        Assertions.assertEquals(2, hints.size, "one hint per key, frontmatter and template: $hints")
    }

    // ── vue-i18n: a template literal key after `useI18n()` ────────────────────────

    private val composable = "import { useI18n } from 'vue-i18n';\nconst { t } = useI18n();\nconst label = t(`order.status.\${status}`);\n"

    /** The key elements the plugin reads from the file's only template literal, each with its type. */
    private fun templateKey(): List<String>? = ReadAction.compute<List<String>?, RuntimeException> {
        val template = PsiTreeUtil.findChildOfType(myFixture.file, JSStringTemplateExpression::class.java)
        Assertions.assertNotNull(template, "no template literal in the file")
        val names = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        val lang = JsLang()
        if (!lang.canExtractKey(template!!, names)) return@compute null
        lang.extractRawKey(template)?.keyElements?.map { "${it.type}:${it.text}" }
    }

    /** What a `.ts` file without `useI18n()` reads from the same call: the static prefix, then the interpolation as a wildcard. */
    private fun keyWithoutComposable(): List<String>? {
        open("plain.ts", "const label = t(`order.status.\${status}`);\n", "TypeScript")
        return templateKey().also {
            Assertions.assertTrue(it.orEmpty().any { element -> element.endsWith(":order.status.") }, "the reference reads the static prefix: $it")
        }
    }

    @Test
    fun `a template literal key after useI18n is read as in any TypeScript file`() {
        val expected = keyWithoutComposable()
        open("order.ts", composable, "TypeScript")
        Assertions.assertEquals(expected, templateKey())
    }

    @Test
    fun `a template literal key in a Vue script setup block is read as in a TypeScript file`() = runVue {
        val expected = keyWithoutComposable()
        myFixture.configureByText("Order.vue", "<script setup lang=\"ts\">\n$composable</script>\n<template><p>{{ label }}</p></template>\n")
        Assertions.assertEquals(expected, templateKey())
    }
}
