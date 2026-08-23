package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What *Apply* writes, now that the summary is a form rather than a paragraph.
 *
 * The wizard used to re-run the deduction at *Apply* time, so anything the user could have
 * corrected on the last step would have been overwritten by the scan. Nothing tested it, because
 * the rule lived inside a dialog that cannot be instantiated headlessly.
 */
class WizardChoicesTest : PlatformBaseTest() {

    @Test
    fun `the root that is stored is the one the form held`() {
        val settings = Settings()

        WizardChoices(translationsRoot = "src/locales").applyTo(settings)

        Assertions.assertEquals("src/locales", settings.translationsRoot)
    }

    @Test
    fun `a null field leaves the setting exactly as it was`() {
        val settings = Settings().apply { defaultNs = "chosen-by-hand" }

        WizardChoices(translationsRoot = "locales").applyTo(settings)

        Assertions.assertEquals("chosen-by-hand", settings.defaultNs, "null means 'leave alone', never 'reset'")
    }

    @Test
    fun `a blank root is not written`() {
        val settings = Settings().apply { translationsRoot = "kept" }

        WizardChoices(translationsRoot = "   ").applyTo(settings)

        Assertions.assertEquals("kept", settings.translationsRoot)
    }

    @Test
    fun `the ticked switches are the ones stored`() {
        val settings = Settings()

        WizardChoices(gettext = true, flatKeys = true, preferredLocalization = "yaml").applyTo(settings)

        Assertions.assertTrue(settings.gettext)
        Assertions.assertTrue(settings.flatKeys)
        Assertions.assertEquals("yaml", settings.preferredLocalization)
    }

    @Test
    fun `an unticked switch stays untouched`() {
        val settings = Settings()

        WizardChoices(defaultNs = "common").applyTo(settings)

        Assertions.assertFalse(settings.gettext, "a switch the user did not tick is not a switch to turn off")
    }

    // -- Modules, chosen instead of a root too wide to mean anything

    @Test
    fun `choosing modules stores them and not the widened root`() {
        val settings = Settings()

        WizardChoices(
            translationsRoot = "apps",
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web/locales"),
                ModuleConfig(name = "api", rootDirectory = "apps/api/locales")
            )
        ).applyTo(settings)

        Assertions.assertEquals(listOf("apps/web/locales", "apps/api/locales"), settings.modules.map { it.rootDirectory })
        Assertions.assertEquals(
            Config().translationsRoot, settings.translationsRoot,
            "storing the wide root alongside the modules puts back the value the user declined"
        )
    }

    @Test
    fun `a module whose folder is already configured is not added twice`() {
        val settings = Settings().apply {
            modules.add(ModuleConfig(name = "existing", rootDirectory = "apps/web/locales"))
        }

        WizardChoices(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web/locales"),
                ModuleConfig(name = "api", rootDirectory = "apps/api/locales")
            )
        ).applyTo(settings)

        Assertions.assertEquals(
            listOf("existing", "api"), settings.modules.map { it.name },
            "the wizard can be reopened; running it twice must not duplicate a catalogue"
        )
    }

    // -- Separators, which the wizard offers pre-filled rather than deduced

    @Test
    fun `an edited separator is stored`() {
        val settings = Settings()

        WizardChoices(keySeparator = "/", nsSeparator = "|").applyTo(settings)

        Assertions.assertEquals("/", settings.keySeparator)
        Assertions.assertEquals("|", settings.nsSeparator)
    }

    @Test
    fun `a blank separator is refused rather than stored`() {
        val settings = Settings()
        val before = settings.keySeparator

        WizardChoices(keySeparator = "").applyTo(settings)

        Assertions.assertEquals(before, settings.keySeparator, "an empty separator leaves every composite key unresolved")
    }
}
