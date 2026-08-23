package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What the wizard proposes, and the evidence it shows for it.
 *
 * The summary used to state "Translations root: apps" in the same tone as a value the user had
 * typed, while `apps` was the longest common prefix of two unrelated catalogues — a guess wider
 * than either of them, presented as a reading. These tests pin down the two things that were
 * missing: whether a value is a guess, and where it came from.
 */
class WizardPlanTest : PlatformBaseTest() {

    private val nested = listOf("locales/en/common.json", "locales/fr/common.json")

    // -- The root, and whether it had to widen

    @Test
    fun `a single catalogue yields one candidate and no widening`() {
        val plan = WizardPlan.of(nested, emptySet())

        Assertions.assertEquals("locales", plan.root.detected)
        Assertions.assertEquals(listOf("locales"), plan.root.candidates)
        Assertions.assertFalse(plan.root.widened, "one catalogue is a reading, not a compromise")
    }

    @Test
    fun `a monorepo reports the folders the common root had to swallow`() {
        val plan = WizardPlan.of(
            listOf("apps/web/locales/en/common.json", "apps/api/locales/en/common.json"),
            emptySet()
        )

        Assertions.assertEquals("apps", plan.root.detected)
        Assertions.assertEquals(listOf("apps/api/locales", "apps/web/locales"), plan.root.candidates)
        Assertions.assertTrue(plan.root.widened, "the value the summary shows must be flagged as a guess")
    }

    @Test
    fun `a root that could not be derived is not a widened one`() {
        val plan = WizardPlan.of(listOf("common.json"), emptySet())

        Assertions.assertEquals(null, plan.root.detected)
        Assertions.assertFalse(plan.root.widened, "nothing was widened: nothing was found")
    }

    // -- Where each deduced value was read

    @Test
    fun `the default namespace names the file it was counted from`() {
        val deduction = WizardPlan.of(nested, emptySet()).deductions
            .single { it.field == WizardPlan.Field.DEFAULT_NS }

        Assertions.assertEquals("common", deduction.value)
        Assertions.assertEquals("locales/en/common.json", deduction.origin)
    }

    @Test
    fun `GetText mode names the catalogue that switched it on`() {
        val deduction = WizardPlan.of(
            listOf("locales/en/common.json", "locales/fr/LC_MESSAGES/messages.po"),
            emptySet()
        ).deductions.single { it.field == WizardPlan.Field.GETTEXT }

        Assertions.assertEquals("locales/fr/LC_MESSAGES/messages.po", deduction.origin)
        Assertions.assertEquals("", deduction.value, "a switch carries no value of its own")
    }

    @Test
    fun `flat keys name the framework rather than a file`() {
        val deduction = WizardPlan.of(nested, setOf("react-intl")).deductions
            .single { it.field == WizardPlan.Field.FLAT_KEYS }

        Assertions.assertEquals("react-intl", deduction.origin, "nothing on disk implies flat keys")
    }

    @Test
    fun `the preferred format names a file of that format`() {
        val deduction = WizardPlan.of(
            listOf("locales/en/common.yaml", "locales/fr/common.yaml"),
            emptySet()
        ).deductions.single { it.field == WizardPlan.Field.PREFERRED_LOCALIZATION }

        Assertions.assertEquals("yaml", deduction.value)
        Assertions.assertEquals("locales/en/common.yaml", deduction.origin)
    }

    /** Nothing scanned means nothing to justify: the form must not offer empty rows. */
    @Test
    fun `an empty scan deduces nothing at all`() {
        Assertions.assertEquals(emptyList<WizardPlan.Deduction>(), WizardPlan.of(emptyList(), setOf("react-intl")).deductions)
    }

    // -- The modules offered instead of a root that is too wide

    @Test
    fun `each module is named after its application, not after its locales folder`() {
        val plan = WizardPlan.of(
            listOf("apps/web/locales/en/common.json", "apps/api/locales/en/common.json"),
            emptySet()
        )

        val modules = WizardPlan.modulesFor(plan.root, listOf(
            "apps/web/locales/en/common.json",
            "apps/api/locales/en/common.json"
        ), emptySet())

        Assertions.assertEquals(listOf("api", "web"), modules.map { it.name })
        Assertions.assertEquals(listOf("apps/api/locales", "apps/web/locales"), modules.map { it.rootDirectory })
    }

    @Test
    fun `each module carries the template its own files follow`() {
        val paths = listOf("apps/web/locales/en/common.json", "apps/api/locales/fr.json")
        val plan = WizardPlan.of(paths, emptySet())

        val templates = WizardPlan.modulesFor(plan.root, paths, emptySet()).associate { it.name to it.pathTemplate }

        Assertions.assertEquals("{lang}/{ns}.json", templates["web"])
        Assertions.assertEquals("{lang}.json", templates["api"], "a file named after a locale is not a namespace")
    }

    @Test
    fun `a GetText module keeps the folder GetText requires`() {
        val paths = listOf("apps/web/locales/fr/LC_MESSAGES/messages.po")
        val plan = WizardPlan.of(paths, emptySet())

        Assertions.assertEquals(
            "{lang}/LC_MESSAGES/{ns}.po",
            WizardPlan.modulesFor(plan.root, paths, emptySet()).single().pathTemplate
        )
    }

    @Test
    fun `the single ticked framework becomes the module preset`() {
        val paths = listOf("apps/web/locales/en/common.json", "apps/api/locales/en/common.json")
        val plan = WizardPlan.of(paths, setOf("i18next"))

        Assertions.assertEquals(listOf("i18next", "i18next"), WizardPlan.modulesFor(plan.root, paths, setOf("i18next")).map { it.preset })
    }

    /** Two frameworks say nothing about which one a given module uses — better to leave it blank. */
    @Test
    fun `several ticked frameworks leave the preset empty`() {
        val paths = listOf("apps/web/locales/en/common.json", "apps/api/locales/en/common.json")
        val plan = WizardPlan.of(paths, setOf("i18next", "vue-i18n"))

        Assertions.assertEquals(
            listOf("", ""),
            WizardPlan.modulesFor(plan.root, paths, setOf("i18next", "vue-i18n")).map { it.preset }
        )
    }

    /** A module resolving to nothing would be one more value shown as configured while configuring nothing. */
    @Test
    fun `a module template resolves to a file that was actually scanned`() {
        val paths = listOf("apps/web/locales/en/common.json")
        val plan = WizardPlan.of(paths, emptySet())
        val module = WizardPlan.modulesFor(plan.root, paths, emptySet()).single()

        Assertions.assertEquals(
            "apps/web/locales/en/common.json",
            ModuleTemplateResolver.resolve(ModuleTemplateResolver.combine(module), "en", "common")
        )
    }
}
