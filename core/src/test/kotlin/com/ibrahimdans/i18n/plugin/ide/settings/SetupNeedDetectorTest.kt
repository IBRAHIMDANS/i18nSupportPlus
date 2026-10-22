package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.SetupNeedDetector.SetupNeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The rule that decides whether the user is interrupted on project opening.
 *
 * It lived inside the startup activity, where nothing could reach it: a `ProjectActivity` is
 * not instantiable headlessly. It was also three cumulative conditions on a pristine project,
 * so a configuration that pointed nowhere never got the suggestion back — the case these tests
 * exist for.
 */
class SetupNeedDetectorTest {

    /** Resolves nothing: every configured root is dead. */
    private val nothingResolves: (String) -> Boolean = { false }

    /** Resolves everything: the configuration is healthy. */
    private val everythingResolves: (String) -> Boolean = { true }

    @Test
    fun `a pristine project is offered the wizard`() {
        assertEquals(SetupNeed.NOT_CONFIGURED, SetupNeedDetector.detect(Config(), nothingResolves))
    }

    @Test
    fun `a project whose root was typed but never resolved is offered the wizard again`() {
        val config = Config(translationsRoot = "public/locales")

        assertEquals(SetupNeed.UNRESOLVED, SetupNeedDetector.detect(config, nothingResolves))
    }

    @Test
    fun `a project whose translations folder has moved is offered the wizard again`() {
        val config = Config(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web/locales")))

        assertEquals(SetupNeed.UNRESOLVED, SetupNeedDetector.detect(config, nothingResolves))
    }

    @Test
    fun `a project the wizard was switched off for is left alone`() {
        val config = Config(setupWizardEnabled = false)

        assertEquals(SetupNeed.NONE, SetupNeedDetector.detect(config, nothingResolves))
    }

    @Test
    fun `a configuration that resolves is left alone`() {
        val config = Config(translationsRoot = "public/locales")

        assertEquals(SetupNeed.NONE, SetupNeedDetector.detect(config, everythingResolves))
    }

    @Test
    fun `one live root among dead ones is enough to stay quiet`() {
        val config = Config(
            translationsRoot = "gone",
            modules = listOf(ModuleConfig(name = "web", rootDirectory = "locales"))
        )

        assertEquals(SetupNeed.NONE, SetupNeedDetector.detect(config) { it == "locales" })
    }

    @Test
    fun `a blank root counts as no root at all`() {
        val config = Config(
            translationsRoot = "   ",
            modules = listOf(ModuleConfig(name = "web", rootDirectory = ""))
        )

        assertEquals(SetupNeed.NOT_CONFIGURED, SetupNeedDetector.detect(config, nothingResolves))
    }

    // -----------------------------------------------------------------------
    // holdsTranslations — the probe the startup activity hands over
    // -----------------------------------------------------------------------

    @Test
    fun `a root holding a translation file resolves`() {
        withTempProject { base ->
            File(base, "public/locales/en").mkdirs()
            File(base, "public/locales/en/common.json").writeText("{}")

            assertTrue(SetupNeedDetector.holdsTranslations(base.path, "public/locales"))
        }
    }

    @Test
    fun `a root that no longer exists does not resolve`() {
        withTempProject { base ->
            assertFalse(SetupNeedDetector.holdsTranslations(base.path, "public/locales"))
        }
    }

    @Test
    fun `a root holding no translation file does not resolve`() {
        withTempProject { base ->
            File(base, "locales").mkdirs()
            File(base, "locales/README.md").writeText("moved to public/locales")

            assertFalse(SetupNeedDetector.holdsTranslations(base.path, "locales"))
        }
    }

    @Test
    fun `an absolute root is read as is`() {
        withTempProject { base ->
            val outside = File(base, "outside/locales").also { it.mkdirs() }
            File(outside, "fr.json").writeText("{}")

            assertTrue(SetupNeedDetector.holdsTranslations(null, outside.absolutePath))
        }
    }

    private fun withTempProject(block: (File) -> Unit) {
        val base = Files.createTempDirectory("setup-need").toFile()
        try {
            block(base)
        } finally {
            base.deleteRecursively()
        }
    }
}
