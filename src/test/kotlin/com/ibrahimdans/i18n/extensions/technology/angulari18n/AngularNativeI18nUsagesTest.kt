package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An id of Angular's native i18n counts as used when the only code naming it is a marker: `i18n="@@id"` in a
 * `.html` template or in a component's inline `template`, or `` $localize`:@@id:text` `` in TypeScript.
 *
 * The usage search reads a key's words in strings, template text and embedded languages only, identifiers and
 * comments left out: each of these markers must still be one of those, or *Clean Up* would offer the id.
 */
class AngularNativeI18nUsagesTest : PlatformBaseTest() {

    private companion object {
        const val ANGULAR_JSON = """{ "version": 1, "projects": { "shop": { "root": "", "sourceRoot": "src",
  "i18n": { "sourceLocale": "en-US", "locales": { "fr": "src/locale/messages.fr.xlf" } } } } }"""

        const val SOURCE = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
  <file source-language="en-US" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="home.title" datatype="html">
        <source>Welcome</source>
      </trans-unit>
      <trans-unit id="cart.unused" datatype="html">
        <source>Nobody reads this</source>
      </trans-unit>
    </body>
  </file>
</xliff>
"""

        const val FRENCH = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
  <file source-language="en-US" target-language="fr" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="home.title" datatype="html">
        <source>Welcome</source>
        <target>Bienvenue</target>
      </trans-unit>
      <trans-unit id="cart.unused" datatype="html">
        <source>Nobody reads this</source>
        <target>Personne ne lit ceci</target>
      </trans-unit>
    </body>
  </file>
</xliff>
"""
    }

    /** The XLIFF files `angular.json` lists, and an Angular project whose `.html` files are templates. */
    private fun addWorkspace() {
        addFileToProject("angular.json", ANGULAR_JSON)
        addFileToProject("src/locale/messages.xlf", SOURCE)
        addFileToProject("src/locale/messages.fr.xlf", FRENCH)
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@angular/localize":"17.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
    }

    /** The Usage column's count of each id. */
    private fun counts(): Map<String, Int> {
        val viewModel = TableViewModel()
        return viewModel.countUsages(project, viewModel.loadRows(project)).associate { it.key to it.usageCount }
    }

    /** The ids *Clean Up Keys…* would offer for deletion. */
    private fun offeredForCleanUp(): Set<String> =
        CleanupUnusedKeysAction().orphans(project).mapTo(mutableSetOf()) { (_, row) -> row.key }

    private fun assertOnlyTheUnusedIdIsOrphan() {
        val counts = counts()
        assertEquals(1, counts["home.title"], "$counts")
        assertEquals(0, counts["cart.unused"], "$counts")
        assertEquals(setOf("cart.unused"), offeredForCleanUp())
    }

    @Test
    fun anIdMarkedInATemplateFileIsUsed() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject(
            "src/app/app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './app.component.html' })
            export class AppComponent {}
            """.trimIndent()
        )
        addFileToProject("src/app/app.component.html", """<p i18n="@@home.title">Hi</p>""")

        assertOnlyTheUnusedIdIsOrphan()
    }

    @Test
    fun anIdMarkedWithAMeaningAndADescriptionIsUsed() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject(
            "src/app/app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './app.component.html' })
            export class AppComponent {}
            """.trimIndent()
        )
        addFileToProject("src/app/app.component.html", """<h1 i18n="site header|The welcome banner@@home.title">Hi</h1>""")

        assertOnlyTheUnusedIdIsOrphan()
    }

    @Test
    fun anIdMarkedInAnInlineTemplateIsUsed() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject(
            "src/app/home.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-home', template: '<p i18n="@@home.title">Hi</p>' })
            export class HomeComponent {}
            """.trimIndent()
        )

        assertOnlyTheUnusedIdIsOrphan()
    }

    @Test
    fun anIdMarkedInLocalizeIsUsed() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject("src/app/home.ts", "export const title = \$localize`:@@home.title:Hi`;\n")

        assertOnlyTheUnusedIdIsOrphan()
    }

    /** The guard of the cases above: with no marker at all, both ids are offered. */
    @Test
    fun withNoMarkerEveryIdIsOrphan() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject("src/app/home.ts", "// home.title is mentioned in a comment only\nexport const homeTitle = 1;\n")

        assertEquals(mapOf("home.title" to 0, "cart.unused" to 0), counts())
        assertEquals(setOf("home.title", "cart.unused"), offeredForCleanUp())
    }
}
