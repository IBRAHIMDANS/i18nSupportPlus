package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What the setup wizard infers from its own scan.
 *
 * It used to store the translations root and nothing else, while its framework checkboxes fed
 * a sentence in the summary and no setting at all: ticking "react-intl" changed nothing, and
 * the project stayed unresolvable until the user found `flatKeys` on their own.
 */
class WizardSettingsDeducerTest : PlatformBaseTest() {

    private fun deduce(files: List<String>, frameworks: Set<String> = emptySet()) =
        WizardSettingsDeducer.deduce(files, frameworks)

    @Test
    fun namespacedFilesGiveTheDefaultNamespace() {
        val deduced = deduce(listOf("locales/en/common.json", "locales/fr/common.json"))

        Assertions.assertEquals("common", deduced.defaultNs)
    }

    /**
     * Ties are broken on convention, then alphabetically — never on the order the file system
     * happened to return.
     */
    @Test
    fun conventionWinsWhenSeveralNamespacesTie() {
        Assertions.assertEquals(
            "translation",
            deduce(listOf("locales/en/auth.json", "locales/en/translation.json")).defaultNs
        )
        Assertions.assertEquals(
            "common",
            deduce(listOf("locales/en/zzz.json", "locales/en/common.json")).defaultNs
        )
        Assertions.assertEquals(
            "auth",
            deduce(listOf("locales/en/billing.json", "locales/en/auth.json")).defaultNs
        )
    }

    /** Files named after locales carry no namespace: nothing to infer, nothing to write. */
    @Test
    fun oneFilePerLocaleInfersNoNamespace() {
        Assertions.assertNull(deduce(listOf("locales/fr.json", "locales/en.json")).defaultNs)
    }

    @Test
    fun poFilesTurnGetTextOn() {
        val deduced = deduce(listOf("locales/fr/LC_MESSAGES/messages.po"))

        Assertions.assertEquals(true, deduced.gettext)
    }

    /** Never inferred as false: a null leaves the user's setting alone. */
    @Test
    fun jsonProjectLeavesGetTextAlone() {
        Assertions.assertNull(deduce(listOf("locales/en/common.json")).gettext)
    }

    /**
     * The link that was missing between the checkboxes and the configuration: FormatJS stores
     * flat ids, which nested resolution can never match.
     */
    @Test
    fun reactIntlTurnsFlatKeysOn() {
        val deduced = deduce(listOf("locales/en.json"), setOf("react-intl"))

        Assertions.assertEquals(true, deduced.flatKeys)
    }

    @Test
    fun otherFrameworksLeaveFlatKeysAlone() {
        Assertions.assertNull(deduce(listOf("locales/en.json"), setOf("i18next")).flatKeys)
    }

    @Test
    fun theMajorityExtensionGivesThePreferredFormat() {
        Assertions.assertEquals("yaml", deduce(listOf("locales/en.yaml", "locales/fr.yml", "locales/x.json")).preferredLocalization)
        Assertions.assertEquals("json", deduce(listOf("locales/en.json", "locales/fr.json5")).preferredLocalization)
    }

    /** No localization reads `.po` — GetText goes through its own setting. */
    @Test
    fun getTextProjectsInferNoPreferredFormat() {
        Assertions.assertNull(deduce(listOf("locales/fr/LC_MESSAGES/messages.po")).preferredLocalization)
    }

    @Test
    fun nothingIsInferredWithoutFiles() {
        val deduced = deduce(emptyList(), setOf("react-intl"))

        Assertions.assertTrue(deduced.isEmpty(), "no files means no configuration to write")
    }

    /** The value Settings auto-picks must not count as a user choice. */
    @Test
    fun theAutoPickedFormatCountsAsUntouched() {
        Assertions.assertTrue(WizardSettingsDeducer.isUntouchedPreferredLocalization(""))
        Assertions.assertFalse(
            WizardSettingsDeducer.isUntouchedPreferredLocalization("something-nobody-registers"),
            "an explicit choice must be left alone"
        )
    }
}
