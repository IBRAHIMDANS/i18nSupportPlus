package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffContentGenerator
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffFileType
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Angular's native i18n: `angular.json` lists the XLIFF file of each locale, `messages.xlf` holds the
 * extracted source text, and the code names a message by its custom id, `i18n="@@id"` in a template and
 * `` $localize`:@@id:text` `` in TypeScript.
 */
class AngularNativeI18nTest : PlatformBaseTest() {

    private companion object {
        const val ANGULAR_JSON = """{ "version": 1, "projects": { "shop": { "root": "", "sourceRoot": "src",
  "i18n": { "sourceLocale": "en-US", "locales": { "fr": "src/locale/messages.fr.xlf", "de": "src/locale/messages.de.xlf" } } } } }"""

        const val SOURCE = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
  <file source-language="en-US" datatype="plaintext" original="ng2.template">
    <body>
      <trans-unit id="home.title" datatype="html">
        <source>Welcome</source>
      </trans-unit>
      <trans-unit id="cart.terms" datatype="html">
        <source>Terms &amp; conditions</source>
      </trans-unit>
      <trans-unit id="cart.greeting" datatype="html">
        <source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!</source>
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
      <trans-unit id="cart.terms" datatype="html">
        <source>Terms &amp; conditions</source>
        <target>Conditions &amp; termes</target>
      </trans-unit>
      <trans-unit id="cart.greeting" datatype="html">
        <source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!</source>
      </trans-unit>
    </body>
  </file>
</xliff>
"""

        const val GERMAN = """<?xml version="1.0" encoding="UTF-8" ?>
<xliff version="2.0" xmlns="urn:oasis:names:tc:xliff:document:2.0" srcLang="en-US" trgLang="de">
  <file id="ngi18n" original="ng.template">
    <unit id="home.title">
      <segment>
        <source>Welcome</source>
        <target>Willkommen</target>
      </segment>
    </unit>
  </file>
</xliff>
"""
    }

    private fun addWorkspace() {
        addFileToProject("angular.json", ANGULAR_JSON)
        addFileToProject("src/locale/messages.xlf", SOURCE)
        addFileToProject("src/locale/messages.fr.xlf", FRENCH)
        addFileToProject("src/locale/messages.de.xlf", GERMAN)
    }

    private fun xliffSources(): List<LocalizationSource> = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
        project.service<LocalizationSourceService>().findAllSources(project).filter { it.name.endsWith(".xlf") }
    }

    private fun keysOf(source: LocalizationSource): List<String> =
        ReadAction.compute<List<String>, RuntimeException> { source.tree!!.entries()!!.map { it.first } }

    private fun valueOf(source: LocalizationSource, id: String): String? =
        ReadAction.compute<String?, RuntimeException> { source.tree!!.findChild(id)?.value()?.let(TranslationPsi::readValue) }

    private fun textOf(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text
    }

    // ── Sources ───────────────────────────────────────────────────────────────

    @Test
    fun xliffFilesHaveTheirOwnType() {
        addWorkspace()
        assertEquals(XliffFileType.INSTANCE, myFixture.findFileInTempDir("src/locale/messages.xlf").fileType)
    }

    @Test
    fun eachFileAngularJsonListsIsASourceOfItsLocale() {
        addWorkspace()
        // An XLIFF export of the plugin, written anywhere, is no source.
        addFileToProject("exports/fr.xlf", FRENCH)

        val sources = xliffSources()

        assertEquals(
            mapOf("messages.xlf" to "en-US", "messages.fr.xlf" to "fr", "messages.de.xlf" to "de"),
            sources.associate { it.name to it.locale },
        )
        assertTrue(sources.none { it.displayPath.startsWith("exports") }, "${sources.map { it.displayPath }}")
        assertTrue(sources.all { it.namespace == null }, "one catalogue per locale, no namespace")
    }

    @Test
    fun withoutI18nInAngularJsonNoFileIsASource() {
        addFileToProject("angular.json", """{ "version": 1, "projects": { "shop": { "root": "" } } }""")
        addFileToProject("src/locale/messages.xlf", SOURCE)
        assertTrue(xliffSources().isEmpty())
    }

    /** The reference reads each unit's source, a locale's file its target: a unit without one is missing there. */
    @Test
    fun theReferenceReadsTheSourcesAndALocaleItsTargets() {
        addWorkspace()
        val byLocale = xliffSources().associateBy { it.locale }

        assertEquals(listOf("home.title", "cart.terms", "cart.greeting"), keysOf(byLocale.getValue("en-US")))
        assertEquals(listOf("home.title", "cart.terms"), keysOf(byLocale.getValue("fr")))
        assertEquals(listOf("home.title"), keysOf(byLocale.getValue("de")))

        assertEquals("Welcome", valueOf(byLocale.getValue("en-US"), "home.title"))
        assertEquals("Conditions & termes", valueOf(byLocale.getValue("fr"), "cart.terms"))
        assertEquals("Willkommen", valueOf(byLocale.getValue("de"), "home.title"))
        assertEquals("""Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!""", valueOf(byLocale.getValue("en-US"), "cart.greeting"))
    }

    // ── Code ──────────────────────────────────────────────────────────────────

    @Test
    fun aLocalizeIdResolvesAndAnUnknownOneIsReported() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        myFixture.configureByText(
            "home.ts",
            "const title = \$localize`:@@home.title:Welcome`;\n" +
                "const unknown = \$localize`:@@<error descr=\"Unresolved key\">home.subtitle</error>:Subtitle`;\n" +
                "const anonymous = \$localize`Not a key`;\n",
        )
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun aLocalizeIdLeadsToItsUnitInEachFile() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        myFixture.configureByText("home.ts", "const title = \$localize`:@@home.ti<caret>tle:Welcome`;")

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val targets = reference.multiResolve(false).mapNotNull { it.element }

        assertEquals(setOf("Welcome", "Bienvenue", "Willkommen"), targets.map { TranslationPsi.readValue(it) }.toSet())
    }

    /**
     * A template is parsed as Angular only inside an Angular project: a component pointing at it through
     * `templateUrl`, with `@angular/core` resolvable.
     */
    private fun openTemplate(template: String) {
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@angular/localize":"17.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        addFileToProject(
            "src/app/app.component.ts",
            """
            import { Component } from '@angular/core';
            @Component({ selector: 'app-root', templateUrl: './app.component.html' })
            export class AppComponent {}
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/app.component.html", template).virtualFile)
        assertTrue(myFixture.file.fileType.name.startsWith("Angular"), "parsed as an Angular template: ${myFixture.file.fileType.name}")
    }

    @Test
    fun anI18nMarkerOfAnAngularTemplateResolves() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        openTemplate(
            """
            <h1 i18n="@@home.title">Welcome</h1>
            <p i18n="cart|The terms@@cart.terms">Terms &amp; conditions</p>
            <img i18n-alt="@@<error descr="Unresolved key">logo.alt</error>" alt="Logo" src="logo.png">
            <p i18n="no custom id">Computed id</p>
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, false, false, true)
    }

    @Test
    fun anI18nMarkerLeadsToItsUnitInEachFile() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        openTemplate("""<h1 i18n="Title of the page@@home.ti<caret>tle">Welcome</h1>""")

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val targets = reference.multiResolve(false).mapNotNull { it.element }

        assertEquals(setOf("Welcome", "Bienvenue", "Willkommen"), targets.map { TranslationPsi.readValue(it) }.toSet())
    }

    @Test
    fun theIdsOfTheReferenceAreCompletedInAMarker() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        openTemplate("""<h1 i18n="@@cart.<caret>">Welcome</h1>""")

        myFixture.completeBasic()

        assertEquals(setOf("cart.terms", "cart.greeting"), myFixture.lookupElementStrings.orEmpty().toSet())
    }

    @Test
    fun aUnitIdLeadsToTheCodeNamingIt() = myFixture.runWithConfig(Config()) {
        addWorkspace()
        addFileToProject("src/app/home.ts", "export const title = \$localize`:@@home.title:Welcome`;\n")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/locale/messages.fr.xlf"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("home.title") + 2)

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val usages = reference.multiResolve(false).mapNotNull { it.element }

        assertEquals(1, usages.size, "$usages")
        assertTrue(usages.single().containingFile.name == "home.ts", "${usages.single().containingFile.name}")
    }

    // ── Tool window ───────────────────────────────────────────────────────────

    /** One row per id across the three locales, each value as its text: an edit must not escape it again. */
    @Test
    fun theTableShowsEachUnitWithItsDecodedText() {
        addWorkspace()

        val rows = TableViewModel().loadRows(project).associateBy { it.key }

        assertEquals(setOf("home.title", "cart.terms", "cart.greeting"), rows.keys)
        assertEquals(mapOf("en-US" to "Terms & conditions", "fr" to "Conditions & termes"), rows.getValue("cart.terms").values)
        assertEquals(mapOf("en-US" to "Welcome", "fr" to "Bienvenue", "de" to "Willkommen"), rows.getValue("home.title").values)
    }

    // ── Missing translations and writing ──────────────────────────────────────

    @Test
    fun aUnitWithoutTargetIsMissingInItsLocale() {
        addWorkspace()

        val missing = KeysSynchronizer().missingEntries(project, Config()).map { it.key to it.locale }.toSet()

        assertEquals(setOf("cart.greeting" to "fr", "cart.terms" to "de", "cart.greeting" to "de"), missing)
    }

    /** The unit is in the file without a target: the target is written after its source. */
    @Test
    fun fillingAMissingTranslationWritesTheTargetOfItsUnit() {
        addWorkspace()
        val entry = KeysSynchronizer().missingEntries(project, Config()).single { it.key == "cart.greeting" && it.locale == "fr" }

        DialogViewModel(project).saveTranslation(entry.source, KeysSynchronizer().buildFullKey(entry.key), "Bonjour & bienvenue")

        val french = textOf("src/locale/messages.fr.xlf")
        val unit = french.substringAfter("""id="cart.greeting"""").substringBefore("</trans-unit>")
        assertTrue(Regex("""</source>\s*<target>Bonjour &amp; bienvenue</target>""").containsMatchIn(unit), unit)
        assertEquals(3, Regex("<trans-unit ").findAll(french).count(), "no unit added: $french")
        assertEquals("Bonjour & bienvenue", valueOf(xliffSources().single { it.locale == "fr" }, "cart.greeting"))
    }

    /** A unit the locale's file lacks is created, its source copied from the reference as written. */
    @Test
    fun aUnitTheFileLacksIsCreatedWithTheReferenceSource() {
        addWorkspace()
        val german = ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
            PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/locale/messages.de.xlf"))!!
        }

        WriteCommandAction.runWriteCommandAction(project) {
            XliffContentGenerator().write(german, "cart.greeting", "Hallo!")
        }

        val text = textOf("src/locale/messages.de.xlf")
        val unit = text.substringAfter("""<unit id="cart.greeting">""", "").substringBefore("</unit>")
        assertTrue(unit.contains("""<source>Hello <x id="INTERPOLATION" equiv-text="{{ name }}"/>!</source>"""), text)
        assertTrue(unit.contains("<target>Hallo!</target>"), text)
        assertTrue(unit.contains("<segment>"), "XLIFF 2 keeps its segments: $text")
        assertEquals("Hallo!", valueOf(xliffSources().single { it.locale == "de" }, "cart.greeting"))
    }

    @Test
    fun anEditedValueIsEscapedAndReadsBackAsTyped() {
        addWorkspace()
        val french = xliffSources().single { it.locale == "fr" }
        val element = ReadAction.compute<com.intellij.psi.PsiElement, RuntimeException> { french.tree!!.findChild("home.title")!!.value() }

        WriteCommandAction.runWriteCommandAction(project) { TranslationPsi.replaceValue(element, "C'est <bon> & beau", project) }

        val target = textOf("src/locale/messages.fr.xlf").substringAfter("""id="home.title"""").substringAfter("<target>").substringBefore("</target>")
        assertTrue(target.endsWith("est &lt;bon&gt; &amp; beau"), target)
        assertEquals("C'est <bon> & beau", valueOf(xliffSources().single { it.locale == "fr" }, "home.title"))
        assertFalse(textOf("src/locale/messages.xlf").contains("bon"), "the reference is left as it was")
    }
}
