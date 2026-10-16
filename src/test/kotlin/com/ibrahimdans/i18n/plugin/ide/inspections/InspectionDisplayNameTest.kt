package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.codeInspection.ex.InspectionToolWrapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Checks that every inspection of the plugin is named by the bundle and described.
 *
 * Their `<localInspection>` entries declare `key=` instead of `displayName=`, which leaves the
 * platform to resolve the name from `<resource-bundle>`. Nothing in Kotlin names those keys, so
 * a wrong one shows up only in *Settings → Editor → Inspections*, as `!inspection.….name!` —
 * or, worse, as the English literal, when a `getDisplayName()` override shadows the bundle the
 * way `ToggleFoldingAction`'s constructor did. A missing `inspectionDescriptions/<shortName>.html`
 * leaves the right-hand pane of that page empty, with nothing logged either.
 *
 * The inspections are taken from the registrar, not from a hand-written table: one added to
 * `plugin.xml` is checked without anyone remembering to list it here.
 */
class InspectionDisplayNameTest : PlatformBaseTest() {

    private companion object {
        const val GROUP = "i18n Support Plus"

        /** `shortName="I18n…"` as declared in a descriptor. */
        val SHORT_NAME = Regex("""shortName="(I18n\w+)"""")
    }

    private fun pluginInspections(): List<InspectionToolWrapper<*, *>> =
        InspectionToolRegistrar.getInstance().createTools().filter { it.groupDisplayName == GROUP }

    @Test
    fun `every inspection declared in plugin xml is registered in the plugin's group`() {
        val descriptor = PluginBundle::class.java.classLoader.getResource("META-INF/plugin.xml")
        Assertions.assertNotNull(descriptor, "META-INF/plugin.xml is not on the classpath")
        val declared = SHORT_NAME.findAll(descriptor!!.readText()).map { it.groupValues[1] }.toSet()
        // Guards the regex itself: an empty set would make this test pass on nothing.
        Assertions.assertTrue(declared.size >= 15, "only ${declared.size} inspections found in plugin.xml: $declared")

        val registered = pluginInspections().map { it.shortName }.toSet()
        Assertions.assertEquals(emptySet<String>(), declared - registered, "declared but not registered in '$GROUP'")
    }

    @Test
    fun `every inspection is named by the bundle`() {
        val inspections = pluginInspections()
        Assertions.assertTrue(inspections.isNotEmpty(), "no inspection registered in '$GROUP'")

        for (wrapper in inspections) {
            val shortName = wrapper.shortName
            val key = wrapper.extension?.key
            Assertions.assertNotNull(key, "inspection $shortName declares no key=: its name is not translated")
            Assertions.assertTrue(PluginBundle.containsKey(key!!), "inspection $shortName names $key, absent from the bundle")

            // Qualified on purpose: BasePlatformTestCase inherits JUnit 3's
            // assertEquals(message, expected, actual), whose argument order is the reverse of
            // the Jupiter one and which would silently win over a static import here.
            val expected = PluginBundle.getMessage(key)
            Assertions.assertEquals(expected, wrapper.displayName, "inspection $shortName does not take its name from $key")
            // The instance itself: an overridden getDisplayName() returning a literal would
            // otherwise be shown wherever the platform asks the tool rather than its descriptor.
            Assertions.assertEquals(expected, wrapper.tool.displayName, "inspection $shortName overrides its bundle name")
        }
    }

    @Test
    fun `every inspection has a description`() {
        for (wrapper in pluginInspections()) {
            val shortName = wrapper.shortName
            val path = "inspectionDescriptions/$shortName.html"
            Assertions.assertNotNull(
                wrapper.tool.javaClass.classLoader.getResource(path),
                "inspection $shortName has no $path"
            )
            Assertions.assertFalse(
                wrapper.loadDescription().isNullOrBlank(),
                "inspection $shortName has an empty description"
            )
        }
    }
}
