package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Checks that the five inspections really are named by the bundle.
 *
 * Their `<localInspection>` entries declare `key=` instead of `displayName=`, which leaves the
 * platform to resolve the name from `<resource-bundle>`. Nothing in Kotlin names those keys, so
 * a wrong one shows up only in *Settings → Editor → Inspections*, as `!inspection.….name!` —
 * or, worse, as the English literal, when a `getDisplayName()` override shadows the bundle the
 * way `ToggleFoldingAction`'s constructor did.
 */
class InspectionDisplayNameTest : PlatformBaseTest() {

    private companion object {
        val NAMES = mapOf(
            "I18nPlaceholderConsistency" to "inspection.placeholder.display.name",
            "I18nIcuFormat" to "inspection.icu.display.name",
            "I18nUnusedKey" to "inspection.unused.display.name",
            "I18nEmptyValue" to "inspection.empty.display.name",
            "I18nDuplicateValue" to "inspection.duplicate.display.name"
        )
    }

    @Test
    fun `every inspection is named by the bundle`() {
        val registered = InspectionToolRegistrar.getInstance().createTools().associateBy { it.shortName }

        for ((shortName, key) in NAMES) {
            val tool = registered[shortName]
            assertNotNull(tool, "inspection $shortName is not registered")

            // Qualified on purpose: BasePlatformTestCase inherits JUnit 3's
            // assertEquals(message, expected, actual), whose argument order is the reverse of
            // the Jupiter one and which would silently win over a static import here.
            Assertions.assertEquals(
                PluginBundle.getMessage(key), tool!!.displayName,
                "inspection $shortName does not take its name from $key"
            )
        }
    }
}
