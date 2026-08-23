package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards the plugin's own translation, which nothing else catches.
 *
 * A key missing from the bundle is not a compile error, and the platform renders it at
 * runtime as `!some.key!` inside the interface without logging anything — so an incomplete
 * extraction ships and is only noticed by whoever happens to open that screen. These tests
 * fail the build instead.
 */
class PluginBundleTest {

    private companion object {
        const val BUNDLE = "messages.I18nBundle"
        val LOCALES = listOf(Locale.FRENCH)

        /** Keys used through the bundle, as they appear in the sources. */
        val KEY_CALL = Regex("""PluginBundle\.(?:message|getMessage)\(\s*"([^"]+)"""")

        /** A MessageFormat argument, as it appears in a bundle value. */
        val PLACEHOLDER = Regex("""\{(\d+)[^}]*}""")
    }

    private fun bundle(locale: Locale): ResourceBundle =
        ResourceBundle.getBundle(BUNDLE, locale, PluginBundleTest::class.java.classLoader)

    /** The English bundle, loaded with a locale no translation exists for. */
    private fun baseBundle(): ResourceBundle = bundle(Locale.ROOT)

    @Test
    fun `every translated bundle covers exactly the base keys`() {
        val base = baseBundle().keySet()

        for (locale in LOCALES) {
            val translated = bundle(locale).keySet()
            assertEquals(
                emptySet<String>(), base - translated,
                "keys missing from I18nBundle_$locale.properties — they would silently fall back to English"
            )
            assertEquals(
                emptySet<String>(), translated - base,
                "keys in I18nBundle_$locale.properties that no longer exist in the base bundle"
            )
        }
    }

    @Test
    fun `translated values are read as UTF-8`() {
        // .properties historically default to ISO-8859-1; a mis-read file would turn every
        // accent into mojibake, which is exactly what a French translation is full of.
        val french = bundle(Locale.FRENCH).getString("toolwindow.action.refresh")

        assertEquals("Actualiser", french)
        assertTrue(
            bundle(Locale.FRENCH).getString("toolwindow.tree.root") == "Traductions",
            "the French bundle must be selected for a French locale, not the base one"
        )
    }

    @Test
    fun `no accented value came back mangled`() {
        val suspicious = bundle(Locale.FRENCH).keySet()
            .map { it to bundle(Locale.FRENCH).getString(it) }
            .filter { (_, value) -> value.contains('�') || value.contains("Ã") }

        assertEquals(emptyList<Pair<String, String>>(), suspicious, "mojibake — the file was not read as UTF-8")
    }

    @Test
    fun `every translated value keeps the placeholders of its base value`() {
        // A translation dropping a {0} loses the path, the name or the count it was meant to
        // carry, and the parity test above cannot see it: the key is there, only its content
        // is wrong. The reader gets a sentence with a hole in it.
        val base = baseBundle()

        for (locale in LOCALES) {
            val translated = bundle(locale)
            val mismatched = base.keySet()
                .map { key -> key to (placeholders(base.getString(key)) to placeholders(translated.getString(key))) }
                .filter { (_, sets) -> sets.first != sets.second }
                .map { (key, sets) -> "$key: expected ${sets.first}, got ${sets.second}" }

            assertEquals(
                emptyList<String>(), mismatched,
                "placeholders lost or invented in I18nBundle_$locale.properties"
            )
        }
    }

    @Test
    fun `every key the sources ask for exists in the bundle`() {
        val base = baseBundle().keySet()
        val missing = sourceFiles()
            .flatMap { file -> KEY_CALL.findAll(file.readText()).map { file.name to it.groupValues[1] } }
            .filterNot { (_, key) -> key in base }
            .toList()

        assertEquals(
            emptyList<Pair<String, String>>(), missing,
            "these keys are asked for in code but absent from the bundle; they would render as !key!"
        )
    }

    /**
     * Walks the Kotlin sources from the module root. Tests run with the project directory as
     * working directory, and the walk is anchored on a directory that must exist, so a wrong
     * root fails loudly rather than silently scanning nothing.
     */
    /** The MessageFormat argument indexes used by [value], e.g. `{0}` and `{1}`. */
    private fun placeholders(value: String): Set<String> =
        PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toSet()

    private fun sourceFiles(): Sequence<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "expected the Kotlin sources at ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }
}
