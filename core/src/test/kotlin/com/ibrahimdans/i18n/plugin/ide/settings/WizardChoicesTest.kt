package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What *Apply* writes, now that the summary is a form rather than a paragraph.
 *
 * The wizard used to re-run the deduction at *Apply* time, so anything the user could have
 * corrected on the last step would have been overwritten by the scan. Nothing tested it, because
 * the rule lived inside a dialog that cannot be instantiated headlessly.
 */
class WizardChoicesTest {

    /** The settings, held in memory: [WizardChoices] only reads and writes a [Config]. */
    private class InMemoryStore(private var config: Config = Config()) : ConfigStore {
        override fun config(): Config = config
        override fun setConfig(config: Config) {
            this.config = config
        }
    }

    @Test
    fun `the root that is stored is the one the form held`() {
        val store = InMemoryStore()

        WizardChoices(translationsRoot = "src/locales").applyTo(store)

        Assertions.assertEquals("src/locales", store.config().translationsRoot)
    }

    @Test
    fun `a null field leaves the setting exactly as it was`() {
        val store = InMemoryStore(Config(defaultNs = "chosen-by-hand"))

        WizardChoices(translationsRoot = "locales").applyTo(store)

        Assertions.assertEquals("chosen-by-hand", store.config().defaultNs, "null means 'leave alone', never 'reset'")
    }

    @Test
    fun `a blank root is not written`() {
        val store = InMemoryStore(Config(translationsRoot = "kept"))

        WizardChoices(translationsRoot = "   ").applyTo(store)

        Assertions.assertEquals("kept", store.config().translationsRoot)
    }

    @Test
    fun `the ticked switches are the ones stored`() {
        val store = InMemoryStore()

        WizardChoices(gettext = true, flatKeys = true, preferredLocalization = "yaml").applyTo(store)

        Assertions.assertTrue(store.config().gettext)
        Assertions.assertTrue(store.config().flatKeys)
        Assertions.assertEquals("yaml", store.config().preferredLocalization)
    }

    @Test
    fun `an unticked switch stays untouched`() {
        val store = InMemoryStore()

        WizardChoices(defaultNs = "common").applyTo(store)

        Assertions.assertFalse(store.config().gettext, "a switch the user did not tick is not a switch to turn off")
    }

    // -- Modules, chosen instead of a root too wide to mean anything

    @Test
    fun `choosing modules stores them and not the widened root`() {
        val store = InMemoryStore()

        WizardChoices(
            translationsRoot = "apps",
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web/locales"),
                ModuleConfig(name = "api", rootDirectory = "apps/api/locales")
            )
        ).applyTo(store)

        Assertions.assertEquals(listOf("apps/web/locales", "apps/api/locales"), store.config().modules.map { it.rootDirectory })
        Assertions.assertEquals(
            Config().translationsRoot, store.config().translationsRoot,
            "storing the wide root alongside the modules puts back the value the user declined"
        )
    }

    @Test
    fun `a module whose folder is already configured is not added twice`() {
        val store = InMemoryStore(Config(modules = listOf(ModuleConfig(name = "existing", rootDirectory = "apps/web/locales"))))

        WizardChoices(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web/locales"),
                ModuleConfig(name = "api", rootDirectory = "apps/api/locales")
            )
        ).applyTo(store)

        Assertions.assertEquals(
            listOf("existing", "api"), store.config().modules.map { it.name },
            "the wizard can be reopened; running it twice must not duplicate a catalogue"
        )
    }

    // -- Separators, which the wizard offers pre-filled rather than deduced

    @Test
    fun `an edited separator is stored`() {
        val store = InMemoryStore()

        WizardChoices(keySeparator = "/", nsSeparator = "|").applyTo(store)

        Assertions.assertEquals("/", store.config().keySeparator)
        Assertions.assertEquals("|", store.config().nsSeparator)
    }

    @Test
    fun `a blank separator is refused rather than stored`() {
        val store = InMemoryStore()
        val before = store.config().keySeparator

        WizardChoices(keySeparator = "").applyTo(store)

        Assertions.assertEquals(before, store.config().keySeparator, "an empty separator leaves every composite key unresolved")
    }
}
