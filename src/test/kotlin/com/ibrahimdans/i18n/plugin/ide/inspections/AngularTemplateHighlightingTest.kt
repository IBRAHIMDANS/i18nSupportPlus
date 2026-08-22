package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Keys behind the `| translate` pipe in a standalone Angular template.
 *
 * A `.html` is only parsed as an Angular template inside an Angular project — a component
 * referencing it through `templateUrl`, with `@angular/core` resolvable. That is what the
 * fixture below sets up; without it the file stays plain HTML and the pipe is inert text,
 * which is the state the README used to describe.
 */
class AngularTemplateHighlightingTest : PlatformBaseTest() {

    private fun angularProject() {
        addFileToProject("assets/translation.json", """{"menu":{"home":"Home"}}""")
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
    fun resolvesAKeyBehindTheTranslatePipe() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        angularProject()
        myFixture.configureByText("tpl.html", """<p>{{ 'menu.home' | translate }}</p>""")
        // Asserted explicitly: "no error reported" is also true of a file parsed as plain HTML,
        // so without this the case would pass even with the template unrecognised.
        Assertions.assertEquals(
            "Angular17Html", myFixture.file.fileType.name,
            "the template must be parsed as an Angular template"
        )
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun reportsAnUnresolvedKeyBehindTheTranslatePipe() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        angularProject()
        myFixture.configureByText(
            "tpl.html",
            """<p>{{ 'menu.<error descr="Unresolved key">missing</error>' | translate }}</p>"""
        )
        myFixture.checkHighlighting(true, false, false, true)
    }
}
