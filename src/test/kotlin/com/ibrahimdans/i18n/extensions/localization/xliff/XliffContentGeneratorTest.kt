package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The `source-language` a new XLIFF file declares: the reference locale of the module it is
 * created in, read back through [XliffDocument].
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class XliffContentGeneratorTest : PlatformBaseTest() {

    private val generator = XliffContentGenerator()

    private val fullKey = FullKey("greet", null, listOf(Literal("greet")))

    private fun sourceLanguageOf(content: String): String? =
        XliffDocument.sourceLanguage(myFixture.configureByText("messages.xlf", content))

    @Test
    fun `a new file declares the reference locale of its module`() {
        val config = Config(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "fr"),
                ModuleConfig(name = "admin", rootDirectory = "apps/admin", referenceLocale = "en"),
            ),
        )

        myFixture.runWithConfig(config) {
            val content = generator.generateContent(project, "${project.basePath}/apps/web/src/locale/messages.xlf", fullKey, "Bonjour")
            Assertions.assertEquals("fr", sourceLanguageOf(content), content)
        }
    }

    @Test
    fun `a file in no module declares the project's reference locale, en without one`() {
        myFixture.runWithConfig(Config(foldingPreferredLanguage = "de")) {
            val content = generator.generateContent(project, "${project.basePath}/src/locale/messages.xlf", fullKey, "Hallo")
            Assertions.assertEquals("de", sourceLanguageOf(content), content)
        }

        Assertions.assertEquals("en", sourceLanguageOf(generator.generateContent(fullKey, "Hello")))
    }
}
