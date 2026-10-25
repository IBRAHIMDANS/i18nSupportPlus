package com.ibrahimdans.i18n.extensions.localization.arb

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Flutter's ARB catalogues: `lib/l10n/app_en.arb` holds the template, with `@@locale` and the
 * `@greeting` metadata describing `greeting`; `app_fr.arb` the translations, often without metadata.
 */
class ArbCataloguesTest : PlatformBaseTest() {

    private companion object {
        const val ENGLISH = """{
  "@@locale": "en",
  "greeting": "Hello {name}",
  "@greeting": {"description": "Shown on the home page", "placeholders": {"name": {"type": "String"}}},
  "farewell": "Goodbye"
}"""
        const val FRENCH = """{
  "greeting": "Bonjour {name}"
}"""
    }

    private fun addCatalogues() {
        myFixture.addFileToProject("lib/l10n/app_en.arb", ENGLISH)
        myFixture.addFileToProject("lib/l10n/app_fr.arb", FRENCH)
    }

    private fun arbSources(): List<LocalizationSource> = ReadAction.compute<List<LocalizationSource>, RuntimeException> {
        project.service<LocalizationSourceService>().findAllSources(project).filter { it.name.endsWith(".arb") }
    }

    // ── Locale ────────────────────────────────────────────────────────────────

    @Test
    fun theLocaleIsTheEndOfTheNameOrTheDeclaredOne() {
        assertEquals("en", ArbCatalogues.localeOf("app_en", null))
        assertEquals("pt_BR", ArbCatalogues.localeOf("app_pt_BR", null))
        assertEquals("fr", ArbCatalogues.localeOf("intl_fr", null))
        assertEquals("fr", ArbCatalogues.localeOf("fr", null))
        assertEquals("de", ArbCatalogues.localeOf("app_en", "de"))
        assertNull(ArbCatalogues.localeOf("messages", null))
    }

    // ── Sources ───────────────────────────────────────────────────────────────

    @Test
    fun eachCatalogueIsASourceOfItsLocaleInTheDefaultNamespace() {
        addCatalogues()

        val sources = arbSources()

        assertEquals(mapOf("app_en.arb" to "en", "app_fr.arb" to "fr"), sources.associate { it.name to it.locale })
        assertTrue(sources.all { it.namespace == null }, "one catalogue per locale, no namespace: $sources")
    }

    @Test
    fun theMetadataIsNoKey() {
        addCatalogues()

        val english = arbSources().single { it.locale == "en" }
        val keys = ReadAction.compute<List<String>, RuntimeException> { english.tree!!.entries()!!.map { it.first } }
        assertEquals(listOf("greeting", "farewell"), keys)

        val file = myFixture.findFileInTempDir("lib/l10n/app_en.arb")
        val leaves = ReadAction.compute<Set<List<String>>, RuntimeException> {
            TranslationFileKeys.translationLeaves(PsiManager.getInstance(project).findFile(file)!!).keys
        }
        assertEquals(setOf(listOf("greeting"), listOf("farewell")), leaves)
    }

    /** Only an ARB catalogue's `@` properties are metadata: a JSON file keeps its `@` keys. */
    @Test
    fun aJsonFileKeepsItsAtKeys() {
        val file = myFixture.configureByText("en.json", """{"@mention": "Mention", "title": "Title"}""")
        val keys = ReadAction.compute<List<String>, RuntimeException> { JsonElementTree.create(file)!!.entries()!!.map { it.first } }
        assertEquals(listOf("@mention", "title"), keys)
    }

    @Test
    fun aCatalogueFlutterBuiltIsNoSource() {
        addCatalogues()
        myFixture.addFileToProject("build/flutter_assets/app_en.arb", ENGLISH)
        myFixture.addFileToProject(".dart_tool/flutter_gen/app_de.arb", ENGLISH)

        val paths = arbSources().map { it.displayPath }
        assertEquals(2, paths.size, "$paths")
        assertTrue(paths.all { it.endsWith("lib/l10n/app_en.arb") || it.endsWith("lib/l10n/app_fr.arb") }, "$paths")
    }

    // ── Table, missing keys, usages ───────────────────────────────────────────

    @Test
    fun theTableShowsTheMissingTranslationWithoutTheMetadata() {
        addCatalogues()

        val rows = TableViewModel().loadRows(project)

        assertEquals(listOf("farewell", "greeting"), rows.map { it.key })
        assertEquals(mapOf("en" to "Goodbye"), rows.single { it.key == "farewell" }.values)
    }

    /** Sync Keys proposes the missing key, never the template's metadata, and writes it to the French catalogue. */
    @Test
    fun syncKeysFillsTheMissingKeyAlone() {
        addCatalogues()

        val missing = KeysSynchronizer().missingEntries(project, Config())

        assertEquals(listOf("farewell" to "fr"), missing.map { it.key to it.locale })
        val entry = missing.single()
        DialogViewModel(project).saveTranslation(entry.source, KeysSynchronizer().buildFullKey(entry.key), "Au revoir")
        val french = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("lib/l10n/app_fr.arb"))!!.text
        assertTrue(french.replace(Regex("\\s"), "").contains("\"farewell\":\"Aurevoir\""), french)
        assertFalse(french.contains("@"), "no metadata copied: $french")
    }

    /** No Dart code is read: an ARB key is never counted unused, nor offered by *Cleanup*. */
    @Test
    fun anArbKeyIsNeverReportedUnused() {
        addCatalogues()
        val viewModel = TableViewModel()

        val counted = viewModel.countUsages(project, viewModel.loadRows(project))

        assertTrue(counted.all { it.usageCount == -1 }, "unscanned: $counted")
        assertEquals(emptyList<Any>(), CleanupUnusedKeysAction().orphans(project, viewModel))
    }
}
