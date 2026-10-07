package com.ibrahimdans.i18n.plugin.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * No compiled class of the plugin names a YAML class, except those of the YAML package.
 *
 * YAML is an optional dependency (`ymlConfig.xml`). Without the YAML plugin its classes cannot be
 * loaded, and the JVM resolves a class reference the moment the instruction holding it runs: an
 * `is YAMLKeyValue`, a `YAMLKeyValue::class.java` — or a `when` on types, which Kotlin compiles to
 * a `typeSwitch` resolving every class it names on its first call — throws NoClassDefFoundError,
 * on a JSON file as much as on a YAML one. Every YAML access therefore lives in
 * `extensions/localization/yaml/`, reached through [TranslationPsi] only for a YAML element.
 *
 * Checked on the bytecode rather than the sources: it is what the JVM loads, and it shows the
 * references a `when` or a class literal hide.
 */
class YamlIsolationTest {

    private val yamlPackage = "com/ibrahimdans/i18n/extensions/localization/yaml/"
    private val yamlReference = "org/jetbrains/yaml/".toByteArray()

    @Test
    fun `no class outside the YAML package references a YAML class`() {
        val classes = pluginClasses()
        assertTrue(classes.size > 100, "the plugin's compiled classes must be found, got ${classes.size}")

        val offending = classes
            .filterKeys { !it.startsWith(yamlPackage) }
            .filterValues { it.contains(yamlReference) }
            .keys.sorted()
        assertEquals(emptyList<String>(), offending)
    }

    /** Every class of the plugin, by its path (`com/…/Name.class`), with its bytes. */
    private fun pluginClasses(): Map<String, ByteArray> {
        // The test class loader gives no code source: locate the classes from a class file's URL,
        // `file:…/classes/…/com/…/TranslationPsi.class` or `jar:file:…!/com/…/TranslationPsi.class`.
        val own = TranslationPsi::class.java.name.replace('.', '/') + ".class"
        val url = requireNotNull(TranslationPsi::class.java.classLoader.getResource(own)) { "$own not found" }
        if (url.protocol == "file") {
            val root = File(url.toURI()).path.removeSuffix(own.replace('/', File.separatorChar)).let(::File)
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
        }
        val jar = File(java.net.URI(url.toString().removePrefix("jar:").substringBefore("!/")))
        return ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("com/ibrahimdans/") && it.name.endsWith(".class") }
                .associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
