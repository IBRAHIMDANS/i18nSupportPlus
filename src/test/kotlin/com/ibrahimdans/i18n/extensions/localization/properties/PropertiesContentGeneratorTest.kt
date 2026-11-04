package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.lang.properties.psi.PropertiesFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The content of a new bundle file: the key written whole, joined by the separator the project
 * configures, read back by the Properties PSI.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class PropertiesContentGeneratorTest : PlatformBaseTest() {

    private val generator = PropertiesLocalization().contentGenerator()

    private val path: String get() = "${project.basePath}/src/main/resources/messages.properties"

    private fun fullKey(separator: String, vararg segments: String): FullKey =
        FullKey(segments.joinToString(separator), null, segments.map { Literal(it) })

    /** The value of [key] in [content] as the Properties PSI reads it; null when no such property parses. */
    private fun valueOf(content: String, key: String): String? =
        (myFixture.configureByText("messages.properties", content) as PropertiesFile).findPropertyByKey(key)?.value

    @Test
    fun `a new file joins the key with the configured separator`() = myFixture.runWithConfig(Config(keySeparator = "/")) {
        val content = generator.generateContent(project, path, fullKey("/", "user", "name"), "Name")

        Assertions.assertEquals("user/name=Name", content)
        Assertions.assertEquals("Name", valueOf(content, "user/name"))
    }

    @Test
    fun `a new file joins the key with a dot by default`() {
        val content = generator.generateContent(project, path, fullKey(".", "user", "name"), "Name")

        Assertions.assertEquals("user.name=Name", content)
        Assertions.assertEquals("Name", valueOf(content, "user.name"))
    }

    @Test
    fun `a key segment holding the format's delimiters reads back as typed`() {
        val content = generator.generateContent(listOf(Literal("a=b"), Literal("c d")), "value")

        Assertions.assertEquals("value", valueOf(content, "a=b.c d"), content)
    }
}
