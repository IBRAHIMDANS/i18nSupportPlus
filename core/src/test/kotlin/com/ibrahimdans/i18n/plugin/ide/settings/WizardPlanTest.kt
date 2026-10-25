package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.ModuleSources
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
class WizardPlanTest {

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
    fun `each module is rooted at and named after its application, not its locales folder`() {
        val plan = WizardPlan.of(
            listOf("apps/web/locales/en/common.json", "apps/api/locales/en/common.json"),
            emptySet()
        )

        val modules = WizardPlan.modulesFor(plan.root, listOf(
            "apps/web/locales/en/common.json",
            "apps/api/locales/en/common.json"
        ), emptySet())

        Assertions.assertEquals(listOf("api", "web"), modules.map { it.name })
        Assertions.assertEquals(listOf("apps/api", "apps/web"), modules.map { it.rootDirectory })
    }

    @Test
    fun `each module carries the template its own files follow`() {
        val paths = listOf("apps/web/locales/en/common.json", "apps/api/locales/fr.json")
        val plan = WizardPlan.of(paths, emptySet())

        val templates = WizardPlan.modulesFor(plan.root, paths, emptySet()).associate { it.name to it.pathTemplate }

        Assertions.assertEquals("locales/{lang}/{ns}.json", templates["web"])
        Assertions.assertEquals("locales/{lang}.json", templates["api"], "a file named after a locale is not a namespace")
    }

    @Test
    fun `a GetText module keeps the folder GetText requires`() {
        val paths = listOf("apps/web/locales/fr/LC_MESSAGES/messages.po")
        val plan = WizardPlan.of(paths, emptySet())

        Assertions.assertEquals(
            "locales/{lang}/LC_MESSAGES/{ns}.po",
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

    // -- The application a module stands for

    private val monorepo = listOf(
        "apps/shop/public/locales/en/common.json",
        "apps/shop/public/locales/fr/common.json",
        "apps/admin/public/locales/en/common.json",
    )

    @Test
    fun `the folder of the application's manifest is the root, the template relative to it`() {
        val plan = WizardPlan.of(monorepo, emptySet())
        val manifests = mapOf("apps/shop" to "{}", "apps/admin" to "{}")

        val modules = WizardPlan.modulesFor(plan.root, monorepo, emptySet(), manifests)

        Assertions.assertEquals(listOf("apps/admin", "apps/shop"), modules.map { it.rootDirectory })
        Assertions.assertEquals(listOf("admin", "shop"), modules.map { it.name })
        Assertions.assertEquals(listOf("public/locales/{lang}/{ns}.json"), modules.map { it.pathTemplate }.distinct())
    }

    @Test
    fun `without a manifest, the asset and translations folders are left out of the root`() {
        val paths = listOf("apps/shop/public/locales/en/common.json", "packages/ui/src/i18n/en.json", "services/api/resources/lang/en.json")
        val plan = WizardPlan.of(paths, emptySet())

        val modules = WizardPlan.modulesFor(plan.root, paths, emptySet())

        Assertions.assertEquals(listOf("apps/shop", "packages/ui", "services/api"), modules.map { it.rootDirectory })
        Assertions.assertEquals(listOf("shop", "ui", "api"), modules.map { it.name })
    }

    @Test
    fun `the nearest manifest wins, the project root's is never one`() {
        val paths = listOf("apps/shop/web/locales/en.json", "apps/blog/locales/en.json")
        val plan = WizardPlan.of(paths, emptySet())
        val manifests = mapOf("apps/shop" to "{}", "apps/shop/web" to "{}")

        val roots = WizardPlan.modulesFor(plan.root, paths, emptySet(), manifests).map { it.rootDirectory }

        Assertions.assertEquals(listOf("apps/blog", "apps/shop/web"), roots)
    }

    @Test
    fun `a code file of the application belongs to its module`() {
        val plan = WizardPlan.of(monorepo, emptySet())
        val modules = WizardPlan.modulesFor(plan.root, monorepo, emptySet(), mapOf("apps/shop" to "{}", "apps/admin" to "{}"))

        val owner = ModuleSources.owner(modules, ModuleSources.FilePath("apps/shop/src/App.tsx", anchored = true))

        Assertions.assertEquals("shop", owner?.name)
        Assertions.assertEquals(
            "apps/shop/public/locales/fr/common.json",
            ModuleTemplateResolver.resolve(ModuleTemplateResolver.combine(owner!!), "fr", "common")
        )
    }

    @Test
    fun `each module takes the framework its own manifest declares`() {
        val paths = listOf("apps/shop/locales/en.json", "apps/blog/locales/en.json", "apps/docs/locales/en.json")
        val plan = WizardPlan.of(paths, setOf("i18next", "vue-i18n"))
        val manifests = mapOf(
            "apps/shop" to """{"dependencies": {"react-i18next": "17"}}""",
            "apps/blog" to """{"dependencies": {"vue-i18n": "11"}}""",
            "apps/docs" to """{"dependencies": {"react": "19"}}""",
        )

        val presets = WizardPlan.modulesFor(plan.root, paths, setOf("i18next", "vue-i18n"), manifests).associate { it.name to it.preset }

        Assertions.assertEquals(mapOf("blog" to "vue-i18n", "docs" to "", "shop" to "i18next"), presets)
    }

    @Test
    fun `two catalogues of one application keep their own roots`() {
        val paths = listOf("apps/shop/public/locales/en.json", "apps/shop/src/i18n/en.json")
        val plan = WizardPlan.of(paths, emptySet())

        val roots = WizardPlan.modulesFor(plan.root, paths, emptySet(), mapOf("apps/shop" to "{}")).map { it.rootDirectory }

        Assertions.assertEquals(listOf("apps/shop/public/locales", "apps/shop/src/i18n"), roots)
    }
}
