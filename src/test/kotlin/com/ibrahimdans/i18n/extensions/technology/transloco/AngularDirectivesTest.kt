package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What Angular templates and Transloco's newer API wrote that was not read: the attribute directives of
 * Transloco and ngx-translate, Transloco 8's `prefix`, `[translocoRead]` on the `<ng-template>` Angular
 * accepts it on, the standalone `translate()` / `translateSignal()`, and completion after the pipes.
 * And a key naming no scope (`'title' | transloco`) resolved in a scope's file, so it was never reported.
 */
class AngularDirectivesTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private enum class Library(val dependency: String) {
        TRANSLOCO("\"@jsverse/transloco\": \"7.0.0\""),
        NGX_TRANSLATE("\"@ngx-translate/core\": \"15.0.0\""),
    }

    private fun angularProject(library: Library) {
        addFileToProject("src/assets/i18n/en.json", """{"menu": {"home": "Home", "about": "About"}, "zzGlobal": "Global"}""")
        if (library == Library.TRANSLOCO) {
            addFileToProject("src/assets/i18n/admin/en.json", """{"title": "Admin", "users": {"list": "Users"}}""")
        }
        addFileToProject("package.json", """{"dependencies": {"@angular/core": "17.0.0", ${library.dependency}}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "src/app/app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './tpl.html' })
            export class AppComponent {}
            """.trimIndent()
        )
    }

    private fun openTemplate(library: Library, template: String) {
        angularProject(library)
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/tpl.html", template).virtualFile)
        Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
    }

    /** The unresolved segments reported in [template]: a key may be annotated as Angular markup and as its expression. */
    private fun unresolvedIn(library: Library, template: String): List<String> {
        openTemplate(library, template)
        return unresolvedSegments()
    }

    private fun unresolvedSegments(): List<String> =
        myFixture.doHighlighting().filter { it.description == unresolved }
            .map { it.startOffset to it.endOffset }.distinct()
            .map { (start, end) -> myFixture.file.text.substring(start, end) }

    private fun completions(library: Library, template: String): List<String> {
        openTemplate(library, template)
        myFixture.complete(CompletionType.BASIC, 1)
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun resolvedAtCaret(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        val reference = myFixture.getReferenceAtCaretPosition()
        val targets = (reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }
            ?: listOfNotNull(reference?.resolve())
        targets.map { it.text.unQuote() }
    }

    // ── Attribute directives ──────────────────────────────────────────────────

    @Test
    fun translocosAttributeDirectiveHoldsAKey() {
        val reported = unresolvedIn(
            Library.TRANSLOCO,
            """
            <span transloco="menu.home"></span>
            <span transloco="menu.zzStatic"></span>
            <span [transloco]="'menu.about'"></span>
            <span [transloco]="'menu.zzBound'"></span>
            <span [transloco]="key"></span>
            <ng-template transloco let-t><p>{{ t('menu.home') }}</p></ng-template>
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("zzStatic", "zzBound"), reported)
    }

    @Test
    fun ngxTranslatesAttributeDirectiveHoldsAKey() {
        val reported = unresolvedIn(
            Library.NGX_TRANSLATE,
            """
            <p translate>menu.home</p>
            <p translate>menu.zzText</p>
            <p translate="menu.about"></p>
            <p [translate]="'menu.zzBound'"></p>
            <p translate="no">Brand name</p>
            <p translate>Hello {{ name }}</p>
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("zzText", "zzBound"), reported)
    }

    @Test
    fun aStaticDirectiveKeyLeadsToItsTranslation() {
        openTemplate(Library.TRANSLOCO, """<span transloco="menu.ho<caret>me"></span>""")
        Assertions.assertEquals(listOf("Home"), resolvedAtCaret())
    }

    @Test
    fun aBoundDirectiveKeyLeadsToItsTranslation() {
        openTemplate(Library.NGX_TRANSLATE, """<span [translate]="'menu.abo<caret>ut'"></span>""")
        Assertions.assertEquals(listOf("About"), resolvedAtCaret())
    }

    @Test
    fun aDirectiveKeyIsCompleted() {
        val offered = completions(Library.TRANSLOCO, """<span transloco="menu.<caret>"></span>""")
        Assertions.assertTrue(offered.any { it.endsWith("home") }, "$offered")
    }

    @Test
    fun aKeyWrittenAsTheElementsTextIsCompleted() {
        val offered = completions(Library.NGX_TRANSLATE, """<p translate>menu.<caret></p>""")
        Assertions.assertTrue(offered.any { it.endsWith("about") }, "$offered")
    }

    // ── Transloco's prefix ────────────────────────────────────────────────────

    @Test
    fun prefixIsReadLikeRead() {
        val reported = unresolvedIn(
            Library.TRANSLOCO,
            """<ng-container *transloco="let t; prefix: 'admin.users'"><p>{{ t('list') }}</p><p>{{ t('zzPrefix') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("zzPrefix"), reported)
    }

    @Test
    fun theReadInputIsReadOnTheNgTemplateAngularAcceptsItOn() {
        val reported = unresolvedIn(
            Library.TRANSLOCO,
            """
            <ng-template transloco let-t [translocoRead]="'admin.users'"><p>{{ t('list') }}</p><p>{{ t('zzRead') }}</p></ng-template>
            <ng-template transloco let-tr [translocoPrefix]="'admin'"><p>{{ tr('title') }}</p><p>{{ tr('zzPrefix') }}</p></ng-template>
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("zzRead", "zzPrefix"), reported)
    }

    // ── Scopes ────────────────────────────────────────────────────────────────

    @Test
    fun aKeyNamingNoScopeIsNotLookedUpInAScope() {
        val reported = unresolvedIn(
            Library.TRANSLOCO,
            """<p>{{ 'title' | transloco }}</p><p>{{ 'admin.title' | transloco }}</p><p>{{ 'menu.home' | transloco }}</p>"""
        )
        Assertions.assertEquals(listOf("title"), reported)
    }

    // ── Standalone functions ──────────────────────────────────────────────────

    @Test
    fun theStandaloneFunctionsHoldAKey() {
        angularProject(Library.TRANSLOCO)
        myFixture.configureFromExistingVirtualFile(
            addFileToProject(
                "src/app/labels.ts",
                """
                import { translate, translateSignal } from '@jsverse/transloco';
                export const home = translate('menu.home');
                export const missing = translate('menu.zzTranslate');
                export const signal = translateSignal('menu.zzSignal');
                """.trimIndent()
            ).virtualFile
        )
        Assertions.assertEquals(listOf("zzTranslate", "zzSignal"), unresolvedSegments())
    }

    @Test
    fun aStandaloneKeyLeadsToItsTranslation() {
        angularProject(Library.TRANSLOCO)
        myFixture.configureFromExistingVirtualFile(
            addFileToProject(
                "src/app/labels.ts",
                "import { translateSignal } from '@jsverse/transloco';\nexport const home = translateSignal('menu.ho<caret>me');"
            ).virtualFile
        )
        Assertions.assertEquals(listOf("Home"), resolvedAtCaret())
    }

    @Test
    fun aTranslateFunctionOfAnotherLibraryIsNotRead() {
        angularProject(Library.TRANSLOCO)
        myFixture.configureFromExistingVirtualFile(
            addFileToProject("src/app/other.ts", "const translate = (s: string) => s;\nexport const x = translate('menu.zzOther');").virtualFile
        )
        Assertions.assertEquals(emptyList<String>(), unresolvedSegments())
    }

    // ── Completion after the pipes ────────────────────────────────────────────

    @Test
    fun theKeysAreCompletedBeforeTheTranslocoPipe() {
        val offered = completions(Library.TRANSLOCO, """<p>{{ '<caret>' | transloco }}</p>""")
        Assertions.assertTrue(offered.containsAll(listOf("menu", "zzGlobal")), "$offered")
    }

    @Test
    fun theKeysAreCompletedBeforeTheTranslatePipe() {
        val offered = completions(Library.NGX_TRANSLATE, """<p>{{ 'menu.<caret>' | translate }}</p>""")
        Assertions.assertTrue(offered.any { it.endsWith("about") }, "$offered")
    }
}
