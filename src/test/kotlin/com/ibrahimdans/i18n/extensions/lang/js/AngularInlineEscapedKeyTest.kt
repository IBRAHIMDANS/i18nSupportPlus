package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularTranslatePipeExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.TranslocoPipeExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A key written `\'key\'` in an inline `template: '…'` quoted with apostrophes: the form the
 * extraction quick fix writes there (double quotes cannot be used inside an attribute's value).
 * The injected fragment keeps the backslashes, so the key must be read without them.
 */
class AngularInlineEscapedKeyTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation", foldingPreferredLanguage = "en")

    private fun withAngularProject(block: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/translation.json", """{"menu":{"home":"Home","help":"Help"}}""")
        addFileToProject(
            "package.json",
            """{"dependencies":{"@angular/core":"17.0.0","@ngx-translate/core":"15.0.0","@jsverse/transloco":"7.0.0"}}"""
        )
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        block()
    }

    /** Opens a component whose inline template is the given JavaScript literal, quotes included. */
    private fun openInline(literal: String) {
        myFixture.configureByText(
            "inline.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: $literal })\n" +
                "export class InlineComponent {}\n"
        )
    }

    /** The string literals of the injected template fragments. */
    private fun injectedLiterals(): List<JSLiteralExpression> = ReadAction.compute<List<JSLiteralExpression>, RuntimeException> {
        val manager = InjectedLanguageManager.getInstance(project)
        val hosts = PsiTreeUtil.collectElementsOfType(myFixture.file, JSLiteralExpression::class.java)
        hosts.flatMap { host ->
            manager.getInjectedPsiFiles(host).orEmpty().flatMap { injected ->
                PsiTreeUtil.collectElementsOfType(injected.first, JSLiteralExpression::class.java).filter { it.isStringLiteral }
            }
        }
    }

    private fun keysOf(extractor: KeyExtractor): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        injectedLiterals().filter(extractor::canExtract).map { literal ->
            extractor.extract(literal).keyElements.joinToString("") { it.text }
        }
    }

    private fun renderedHints(): List<String> {
        myFixture.doHighlighting()
        return ReadAction.compute<List<String>, RuntimeException> {
            myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength)
                .mapNotNull { it.renderer as? DeclarativeInlayRenderer }
                .map { it.toString() }
        }
    }

    @Test
    fun `an escaped key of the translate pipe is read without its backslashes`() = withAngularProject {
        openInline("""'<p>{{ \'menu.home\' | translate }}</p>'""")
        Assertions.assertEquals(listOf("menu.home"), keysOf(AngularTranslatePipeExtractor()))
    }

    @Test
    fun `an escaped key of the transloco pipe is read without its backslashes`() = withAngularProject {
        openInline("""'<p>{{ \'menu.help\' | transloco }}</p>'""")
        Assertions.assertEquals(listOf("menu.help"), keysOf(TranslocoPipeExtractor()))
    }

    @Test
    fun `the key the quick fix writes into an attribute is read`() = withAngularProject {
        openInline("""'<input [placeholder]="\'menu.home\' | translate">'""")
        Assertions.assertEquals(listOf("menu.home"), keysOf(AngularTranslatePipeExtractor()))
    }

    @Test
    fun `an escaped key gets its inlay hint`() = withAngularProject {
        openInline("""'<p>{{ \'menu.home\' | translate }}</p>'""")
        val hints = renderedHints()
        Assertions.assertEquals(1, hints.size, "one hint for the key: $hints")
    }

    @Test
    fun `keys of a backtick or double-quoted template are unchanged`() = withAngularProject {
        openInline("""`<p>{{ 'menu.home' | translate }}</p>`""")
        Assertions.assertEquals(listOf("menu.home"), keysOf(AngularTranslatePipeExtractor()))
        openInline(""""<p>{{ 'menu.help' | transloco }}</p>"""")
        Assertions.assertEquals(listOf("menu.help"), keysOf(TranslocoPipeExtractor()))
    }
}
