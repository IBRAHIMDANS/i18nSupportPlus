package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.Properties

class CoreMessagesTest {

    @AfterEach
    fun restoreLocale() {
        CoreMessages.locale = Locale::getDefault
    }

    @Test
    fun `every key the core sources ask for exists in English and French`() {
        val used = sources()
            .flatMap { CALL.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()
        assertTrue(used.isNotEmpty(), "no CoreMessages.message call found: the scan is broken")

        for (bundle in listOf("I18nCoreBundle.properties", "I18nCoreBundle_fr.properties")) {
            val missing = used - load(bundle).stringPropertyNames()
            assertTrue(missing.isEmpty(), "$bundle lacks $missing")
        }
    }

    @Test
    fun `the French bundle covers exactly the base keys`() {
        assertEquals(
            load("I18nCoreBundle.properties").stringPropertyNames(),
            load("I18nCoreBundle_fr.properties").stringPropertyNames(),
        )
    }

    @Test
    fun `a message is read in the locale it is given, its quotes and parameters substituted`() {
        CoreMessages.locale = { Locale.FRENCH }

        assertEquals(
            "CSV mal formé : la première colonne doit être 'key', mais vaut 'id'",
            CoreMessages.message("csv.error.first.column", "key", "id"),
        )
        assertEquals("CSV vide : aucune ligne d'en-tête", CoreMessages.message("csv.error.no.header"))
    }

    @Test
    fun `a locale without its own file reads the base bundle`() {
        CoreMessages.locale = { Locale.GERMAN }

        assertEquals("Empty CSV: no header row", CoreMessages.message("csv.error.no.header"))
    }

    private fun load(name: String): Properties = Properties().apply {
        val stream = CoreMessages::class.java.getResourceAsStream("/messages/$name")
        requireNotNull(stream) { "missing resource /messages/$name" }.reader(Charsets.UTF_8).use { load(it) }
    }

    /**
     * The Kotlin sources of `core`. Gradle runs the tests from the module's directory, and the
     * walk is anchored on a directory that must exist, so a wrong root fails loudly rather than
     * silently scanning nothing.
     */
    private fun sources(): Sequence<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "expected the Kotlin sources at ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }

    private companion object {
        val CALL = Regex("""CoreMessages\.message\(\s*"([^"]+)"""")
    }
}
