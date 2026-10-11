package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.actions.translationFileFor
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tool window over a Laravel project: `lang/<locale>/<file>.php` and a package's overrides,
 * `lang/vendor/<package>/<locale>/<file>.php`, read, shown, edited and exchanged as CSV.
 */
class LaravelToolWindowTest : PlatformBaseTest() {

    private fun laravelProject() {
        addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
        addFileToProject(
            "lang/en/messages.php",
            "<?php\n\nreturn [\n    'welcome' => 'Welcome',\n    'auth' => [\n        'failed' => 'Wrong credentials',\n    ],\n];\n",
        )
        addFileToProject("lang/fr/messages.php", "<?php\n\nreturn [\n    'welcome' => 'Bienvenue',\n];\n")
        addFileToProject("lang/vendor/courier/en/messages.php", "<?php\n\nreturn [\n    'welcome' => 'Welcome from Courier',\n];\n")
    }

    private fun documentText(path: String): String =
        FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path))!!.text

    @Test
    fun theKeysOfThePhpFilesAreLoaded() {
        laravelProject()

        val translations = TranslationDataLoader.loadAllTranslations(project)

        assertEquals(mapOf("en" to "Welcome", "fr" to "Bienvenue"), translations["messages:welcome"])
        assertEquals(mapOf("en" to "Wrong credentials"), translations["messages:auth.failed"])
        // The override is a namespace of its own, not mixed into the application's `messages`.
        assertEquals(mapOf("en" to "Welcome from Courier"), translations["courier::messages:welcome"])
        assertEquals(3, translations.size, translations.keys.toString())
    }

    @Test
    fun theTreeGroupsTheOverrideUnderItsPackageNamespace() {
        laravelProject()

        val root = TreeViewModel().loadTranslations(project)

        val groups = root.children.values.associateBy { it.key }
        assertEquals(setOf("messages", "courier::messages"), groups.keys)
        assertEquals(setOf("welcome"), groups.getValue("courier::messages").children.values.map { it.key }.toSet())
        assertEquals(setOf("welcome", "auth"), groups.getValue("messages").children.values.map { it.key }.toSet())
    }

    @Test
    fun theTableShowsTheOverrideUnderItsPackageNamespace() {
        laravelProject()
        val viewModel = TableViewModel()

        val rows = viewModel.loadRows(project)

        val override = rows.single { it.key == "courier::messages:welcome" }
        assertEquals("courier::messages", viewModel.namespaceOf(override.key))
        assertEquals("welcome", viewModel.keyLabel(override.key))
        assertEquals("messages", viewModel.namespaceOf("messages:welcome"))
    }

    @Test
    fun theStatsCountThePhpKeysPerNamespace() {
        laravelProject()

        val report = TranslationStatsAnalyzer.report(project)

        assertEquals(listOf("en", "fr"), report.locales)
        assertEquals(3, report.total.total)
        val namespaces = report.namespaces.associate { it.group to it.total }
        assertEquals(mapOf(NamespaceFilter.Named("courier::messages") to 1, NamespaceFilter.Named("messages") to 2), namespaces)
        assertEquals(listOf("messages:auth.failed"), report.namespaces.single { it.group == NamespaceFilter.Named("messages") }.of("fr")?.missingKeys)
    }

    @Test
    fun anEditOfTheOverrideIsWrittenToTheOverride() {
        laravelProject()

        assertTrue(TableViewModel().saveValue(project, "courier::messages:welcome", "en", "Hello from Courier"))

        assertTrue(documentText("lang/vendor/courier/en/messages.php").contains("'Hello from Courier'"))
        assertFalse(documentText("lang/en/messages.php").contains("Hello from Courier"), "the application's file is untouched")
        assertTrue(documentText("lang/en/messages.php").contains("'Welcome'"))
    }

    @Test
    fun anEditOfTheApplicationIsWrittenToTheApplication() {
        laravelProject()

        assertTrue(TableViewModel().saveValue(project, "messages:welcome", "en", "Hi"))

        assertTrue(documentText("lang/en/messages.php").contains("'Hi'"))
        assertTrue(documentText("lang/vendor/courier/en/messages.php").contains("'Welcome from Courier'"), "the override is untouched")
    }

    @Test
    fun anEscapedQuoteIsReadAndWrittenOnce() {
        laravelProject()
        addFileToProject("lang/de/messages.php", "<?php\n\nreturn [\n    'welcome' => 'It\\'s here',\n];\n")

        assertEquals("It's here", TranslationDataLoader.loadAllTranslations(project)["messages:welcome"]?.get("de"))
        assertTrue(TableViewModel().saveValue(project, "messages:welcome", "de", "It's back"))

        assertTrue(documentText("lang/de/messages.php").contains("'It\\'s back'"), documentText("lang/de/messages.php"))
    }

    @Test
    fun theCsvRoundTripRoutesEachKeyToItsFile() {
        laravelProject()
        val existing = TranslationDataLoader.loadAllTranslations(project)
        val csv = CsvTranslationCodec.encode(listOf("en", "fr"), existing)
        assertTrue(csv.contains("courier::messages:welcome"), csv)

        val edited = csv
            .replace("Welcome from Courier", "Courier says hi")
            .replace("Wrong credentials", "Bad credentials")
        val plan = CsvTranslationCodec.computeImportPlan(existing, listOf("en", "fr"), CsvTranslationCodec.parse(edited))
        assertEquals(setOf("courier::messages:welcome", "messages:auth.failed"), plan.entries.map { it.key }.toSet())

        // Applied the way ImportTranslationsAction applies a plan.
        val sources = TranslationDataLoader.findSources(project)
        val config = Settings.getInstance(project).config()
        val operations = plan.entries.map { entry ->
            val source = translationFileFor(entry.key, entry.locale, sources)
            assertNotNull(source, entry.key)
            Triple(source!!, KeysSynchronizer().buildFullKey(entry.key, config), entry.value)
        }
        val targets = operations.associate { (source, fullKey, _) -> fullKey.source to source.displayPath }
        assertTrue(targets.getValue("courier::messages:welcome").endsWith("lang/vendor/courier/en/messages.php"), targets.toString())
        assertTrue(targets.getValue("messages:auth.failed").endsWith("lang/en/messages.php"), targets.toString())
        assertFalse(targets.getValue("messages:auth.failed").contains("vendor"), targets.toString())
        val viewModel = DialogViewModel(project)
        WriteCommandAction.runWriteCommandAction(project) {
            for ((source, fullKey, value) in operations) viewModel.saveTranslation(source, fullKey, value)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val reloaded = TranslationDataLoader.loadAllTranslations(project)
        assertEquals(mapOf("en" to "Courier says hi"), reloaded["courier::messages:welcome"])
        assertEquals(mapOf("en" to "Bad credentials"), reloaded["messages:auth.failed"])
        assertEquals(mapOf("en" to "Welcome", "fr" to "Bienvenue"), reloaded["messages:welcome"])
    }
}
