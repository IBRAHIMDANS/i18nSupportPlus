package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The value of a key shown where Angular writes it outside a call or a pipe: the attribute directives
 * of ngx-translate and Transloco (`<p translate>key</p>`, `transloco="key"`, `[transloco]="'key'"`), and
 * the keys of a component's inline `template: '…'`.
 *
 * Through the platform's own passes, as [TemplateEditorFeaturesTest] does: the question is whether
 * the editor shows the value, not whether our builder returns it when called by hand.
 */
class AngularDirectiveValuesTest : PlatformBaseTest() {

    private val config = Config(
        defaultNs = "translation",
        foldingPreferredLanguage = "en",
        foldingMaxLength = 20,
        foldingEnabled = true,
    )

    private fun withAngularProject(block: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/translation.json", """{"menu":{"home":"Home","help":"Help","about":"About"}}""")
        addFileToProject(
            "package.json",
            """{"dependencies":{"@angular/core":"17.0.0","@ngx-translate/core":"15.0.0","@jsverse/transloco":"7.0.0"}}"""
        )
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        block()
    }

    private fun openTemplate(template: String) {
        addFileToProject(
            "app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './tpl.html' })
            export class AppComponent {}
            """.trimIndent()
        )
        myFixture.configureByText("tpl.html", template)
        Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
    }

    private fun openInlineComponent(template: String) {
        myFixture.configureByText(
            "inline.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: `$template` })\n" +
                "export class InlineComponent {}\n"
        )
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

    /** The folded regions the folding manager built in the editor, as `folded text -> placeholder`. */
    private fun foldedRegions(): Map<String, String> {
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val text = myFixture.editor.document.text
        return myFixture.editor.foldingModel.allFoldRegions
            .associate { text.substring(it.startOffset, it.endOffset) to it.placeholderText }
    }

    // ── Folding of the directives ────────────────────────────────────────────────

    @Test
    fun `ngx-translate's directive folds the key written as the element's text`() = withAngularProject {
        openTemplate("""<p translate>menu.home</p>""")
        Assertions.assertEquals("Home", foldedRegions()["menu.home"], "${foldedRegions()}")
    }

    @Test
    fun `ngx-translate's directive folds the key written as its value`() = withAngularProject {
        openTemplate("""<p translate="menu.about"></p>""")
        Assertions.assertEquals("About", foldedRegions()["menu.about"], "${foldedRegions()}")
    }

    @Test
    fun `transloco's directive folds its key`() = withAngularProject {
        openTemplate("""<span transloco="menu.help"></span>""")
        Assertions.assertEquals("Help", foldedRegions()["menu.help"], "${foldedRegions()}")
    }

    @Test
    fun `a bound directive folds its key`() = withAngularProject {
        openTemplate("""<span [transloco]="'menu.help'"></span>""")
        Assertions.assertEquals("Help", foldedRegions()["'menu.help'"], "${foldedRegions()}")
    }

    @Test
    fun `a directive with no key folds nothing`() = withAngularProject {
        openTemplate("""<p translate="no">Brand</p><p translate>Hello {{ name }}</p><span transloco="menu.zzMissing"></span>""")
        Assertions.assertEquals(emptyMap<String, String>(), foldedRegions().filterValues { it in setOf("Home", "Help", "About") })
        Assertions.assertFalse(foldedRegions().keys.any { "Brand" in it || "zzMissing" in it || "Hello" in it }, "${foldedRegions()}")
    }

    @Test
    fun `the pipes still fold whole`() = withAngularProject {
        openTemplate("""<p>{{ 'menu.home' | translate }}</p>""")
        Assertions.assertEquals("Home", foldedRegions()["'menu.home' | translate"], "${foldedRegions()}")
    }

    // ── Hints on the directives ──────────────────────────────────────────────────

    @Test
    fun `each directive key gets an inlay hint`() = withAngularProject {
        openTemplate("""<p translate>menu.home</p><span transloco="menu.help"></span><span [transloco]="'menu.about'"></span>""")
        val hints = renderedHints()
        Assertions.assertEquals(3, hints.size, "one hint per key: $hints")
    }

    // ── Inline templates ─────────────────────────────────────────────────────────

    @Test
    fun `a key of an inline template gets an inlay hint`() = withAngularProject {
        openInlineComponent("""<p>{{ 'menu.home' | translate }}</p>""")
        val hints = renderedHints()
        Assertions.assertEquals(1, hints.size, "one hint for the key: $hints")
    }

    @Test
    fun `a directive key of an inline template gets an inlay hint`() = withAngularProject {
        openInlineComponent("""<span transloco="menu.help"></span>""")
        val hints = renderedHints()
        Assertions.assertEquals(1, hints.size, "one hint for the key: $hints")
    }

    @Test
    fun `a key of an inline template folds`() = withAngularProject {
        openInlineComponent("""<p>{{ 'menu.home' | translate }}</p>""")
        Assertions.assertTrue("Home" in foldedRegions().values, "${foldedRegions()}")
    }

    @Test
    fun `a directive key of an inline template folds`() = withAngularProject {
        openInlineComponent("""<span transloco="menu.help"></span>""")
        Assertions.assertEquals("Help", foldedRegions()["menu.help"], "${foldedRegions()}")
    }

    @Test
    fun `a component's own key and its inline template's key get one hint each`() = withAngularProject {
        myFixture.configureByText(
            "own.component.ts",
            "import { Component } from '@angular/core';\n" +
                "import { t } from 'i18next';\n" +
                "@Component({ selector: 'app-own', template: `<p>{{ 'menu.home' | translate }}</p>` })\n" +
                "export class OwnComponent { title = t('menu.help'); }\n"
        )
        val hints = renderedHints()
        Assertions.assertEquals(2, hints.size, "one hint per key: $hints")
    }
}
