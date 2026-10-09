package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspections.customHighlightingCheck
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Angular Transloco: the service calls, the `| transloco` pipe and the structural directive's `t`. */
class TranslocoTest : PlatformBaseTest() {

    private val translation = """{"menu": {"home": "Home"}}"""

    @Test
    fun serviceTranslateResolvesAKey() = myFixture.customHighlightingCheck(
        "resolved.ts",
        """const a = this.translocoService.translate("menu.home")""",
        "assets/translation.json",
        translation
    )

    @Test
    fun serviceCallsReportAnUnresolvedKey() = myFixture.customHighlightingCheck(
        "unresolved.ts",
        """
        const a = this.translocoService.translate("menu.<error descr="Unresolved key">missing</error>");
        const b = transloco.selectTranslate("menu.<error descr="Unresolved key">gone</error>");
        """.trimIndent(),
        "assets/translation.json",
        translation
    )

    /** `translate` alone is too common a method name: only a Transloco qualifier claims it. */
    @Test
    fun aTranslateMethodOfAnotherObjectIsNotAKey() = myFixture.customHighlightingCheck(
        "other.ts",
        """const a = this.geometry.translate("menu.missing")""",
        "assets/translation.json",
        translation
    )

    private fun angularProject() {
        addFileToProject("assets/translation.json", translation)
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@jsverse/transloco":"7.0.0"}}""")
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

    private fun checkTemplate(template: String) = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        angularProject()
        myFixture.configureByText("tpl.html", template)
        Assertions.assertEquals("Angular17Html", myFixture.file.fileType.name, "the template must be parsed as an Angular template")
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun thePipeResolvesAKey() = checkTemplate("""<p>{{ 'menu.home' | transloco }}</p>""")

    @Test
    fun thePipeReportsAnUnresolvedKey() =
        checkTemplate("""<p>{{ 'menu.<error descr="Unresolved key">missing</error>' | transloco }}</p>""")

    @Test
    fun theDirectiveFunctionResolvesAKey() = checkTemplate(
        """<ng-container *transloco="let t"><p>{{ t('menu.home') }}</p><p>{{ t('menu.<error descr="Unresolved key">missing</error>') }}</p></ng-container>"""
    )

    @Test
    fun translocoIsDetectedFromEitherPackageName() {
        Assertions.assertTrue("transloco" in FrameworkDetector.detect("""{"dependencies":{"@jsverse/transloco":"7.0.0"}}"""))
        Assertions.assertTrue("transloco" in FrameworkDetector.detect("""{"dependencies":{"@ngneat/transloco":"4.0.0"}}"""))
        Assertions.assertFalse("transloco" in FrameworkDetector.detect("""{"dependencies":{"@ngx-translate/core":"15.0.0"}}"""))
    }
}
