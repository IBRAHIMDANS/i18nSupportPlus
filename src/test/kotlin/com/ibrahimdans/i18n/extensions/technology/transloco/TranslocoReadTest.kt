package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The `read` of `*transloco="let t; read: 'admin'"`: a key prefix, whose first segment may name a scope. */
class TranslocoReadTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun translocoProject() {
        addFileToProject("src/assets/i18n/en.json", """{"menu": {"home": "Home", "zzAbout": "About"}, "zzGlobal": "Global"}""")
        addFileToProject("src/assets/i18n/admin/en.json", """{"title": "Admin", "zzUnused": "Unused", "users": {"list": "Users"}}""")
        addFileToProject("package.json", """{"dependencies": {"@angular/core": "17.0.0", "@jsverse/transloco": "7.0.0"}}""")
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

    private fun openTemplate(template: String) {
        translocoProject()
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/tpl.html", template).virtualFile)
        Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
    }

    /** The keys of [template] reported unresolved, as written. */
    private fun unresolvedIn(template: String): List<String> {
        openTemplate(template)
        // The template is annotated as Angular and as JavaScript: one key may be reported twice.
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { it.startOffset to it.endOffset }.distinct()
            .map { (start, end) -> myFixture.file.text.substring(start, end) }
    }

    @Test
    fun aReadNamingAScopeResolvesInTheScopeFile() {
        val reported = unresolvedIn(
            """<ng-container *transloco="let t; read: 'admin'"><p>{{ t('title') }}</p><p>{{ t('users.list') }}</p><p>{{ t('zzMissing') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("zzMissing"), reported)
    }

    @Test
    fun aReadGoingDeeperThanTheScopeIsAPrefixInIt() {
        val reported = unresolvedIn(
            """<ng-container *transloco="let t; read: 'admin.users'"><p>{{ t('list') }}</p><p>{{ t('title') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("title"), reported)
    }

    @Test
    fun aReadNamingNoScopeIsAPrefixOfAGlobalKey() {
        val reported = unresolvedIn(
            """<ng-container *transloco="let t; read: 'menu'"><p>{{ t('home') }}</p><p>{{ t('zzGlobal') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("zzGlobal"), reported)
    }

    @Test
    fun withoutReadKeysAreReadAsBefore() {
        val reported = unresolvedIn(
            """<ng-container *transloco="let t"><p>{{ t('menu.home') }}</p><p>{{ t('admin.title') }}</p><p>{{ t('home') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("home"), reported)
    }

    /** The directive publishing the function closest to the call decides. */
    @Test
    fun anInnerDirectiveWithoutReadHidesTheOuterRead() {
        val reported = unresolvedIn(
            """
            <ng-container *transloco="let t; read: 'menu'">
              <p>{{ t('home') }}</p>
              <div *transloco="let t"><p>{{ t('menu.home') }}</p><p>{{ t('home') }}</p></div>
              <div *transloco="let tr; read: 'admin'"><p>{{ tr('title') }}</p><p>{{ t('home') }}</p></div>
            </ng-container>
            """.trimIndent()
        )
        // Only `home` under the inner directive without `read` is reported: the outer prefix stops there.
        Assertions.assertEquals(listOf("home"), reported, "reported: $reported")
    }

    @Test
    fun theCallNavigatesToTheScopeKey() {
        openTemplate("""<ng-container *transloco="let t; read: 'admin'"><p>{{ t('ti<caret>tle') }}</p></ng-container>""")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val targets = generateSequence(element) { it.parent }.take(4)
            .flatMap { it.references.asSequence() }
            .flatMap { ref -> (ref as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }?.asSequence() ?: sequenceOf(ref.resolve()).filterNotNull() }
            .mapNotNull { PsiTreeUtil.getParentOfType(it, JsonProperty::class.java, false) }
            .toList()
        Assertions.assertTrue(
            targets.any { it.name == "title" && it.containingFile.virtualFile.path.endsWith("i18n/admin/en.json") },
            "the call must lead to the scope file's key: $targets"
        )
    }

    @Test
    fun theScopeKeysAreCompleted() {
        openTemplate("""<ng-container *transloco="let t; read: 'admin'"><p>{{ t('<caret>') }}</p></ng-container>""")
        myFixture.complete(CompletionType.BASIC, 1)
        val offered = myFixture.lookupElementStrings.orEmpty()
        Assertions.assertTrue(offered.containsAll(listOf("title", "zzUnused")), "the scope's keys must be offered: $offered")
        Assertions.assertFalse("zzGlobal" in offered, "a global key must not be offered: $offered")
    }

    private fun unusedIn(path: String): List<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        val message = PluginBundle.getMessage("inspection.unused.message")
        return myFixture.doHighlighting().filter { it.description == message }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset).trim('"') }
    }

    @Test
    fun aScopeKeyReadThroughReadIsNotUnused() {
        translocoProject()
        addFileToProject(
            "src/app/tpl.html",
            """<ng-container *transloco="let t; read: 'admin'"><p>{{ t('title') }}</p><p>{{ t('users.list') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("zzUnused"), unusedIn("src/assets/i18n/admin/en.json"))
    }

    @Test
    fun aKeyReadThroughAReadGoingIntoAScopeIsNotUnused() {
        translocoProject()
        addFileToProject(
            "src/app/tpl.html",
            """<ng-container *transloco="let t; read: 'admin.users'"><p>{{ t('list') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("title", "zzUnused"), unusedIn("src/assets/i18n/admin/en.json"))
    }

    @Test
    fun aGlobalKeyReadThroughReadIsNotUnused() {
        translocoProject()
        addFileToProject(
            "src/app/tpl.html",
            """<ng-container *transloco="let t; read: 'menu'"><p>{{ t('home') }}</p></ng-container>"""
        )
        Assertions.assertEquals(listOf("zzAbout", "zzGlobal"), unusedIn("src/assets/i18n/en.json"))
    }
}
