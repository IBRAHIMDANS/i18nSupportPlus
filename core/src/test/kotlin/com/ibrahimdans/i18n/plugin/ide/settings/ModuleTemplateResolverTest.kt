package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.IssueKind
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.RootStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModuleTemplateResolverTest {

    @Test
    fun testCombineJoinsRootAndTemplatesWithoutDoubleSlashes() {
        val module = ModuleConfig(
            rootDirectory = "src/locales/",
            pathTemplate = "/{lang}/",
            fileTemplate = "{ns}.json"
        )

        assertEquals("src/locales/{lang}/{ns}.json", ModuleTemplateResolver.combine(module))
    }

    @Test
    fun testCombineSkipsBlankParts() {
        val module = ModuleConfig(rootDirectory = "src/locales", pathTemplate = "{lang}/{ns}.json")

        assertEquals("src/locales/{lang}/{ns}.json", ModuleTemplateResolver.combine(module))
    }

    @Test
    fun testResolveSubstitutesLanguageAndNamespace() {
        assertEquals(
            "src/locales/fr/common.json",
            ModuleTemplateResolver.resolve("src/locales/{lang}/{ns}.json", "fr", "common")
        )
    }

    @Test
    fun testResolveAcceptsTheLongPlaceholderNames() {
        assertEquals(
            "fr/common.json",
            ModuleTemplateResolver.resolve("{locale}/{namespace}.json", "fr", "common")
        )
    }

    @Test
    fun testResolveIsCaseAndSpaceTolerant() {
        assertEquals("fr.json", ModuleTemplateResolver.resolve("{ LANG }.json", "fr", "common"))
    }

    @Test
    fun testResolveLeavesAnUnknownPlaceholderAsWritten() {
        assertEquals("fr/{domain}.json", ModuleTemplateResolver.resolve("{lang}/{domain}.json", "fr", "common"))
    }

    @Test
    fun testBlankTemplateIsReportedOnItsOwn() {
        assertEquals(listOf(ModuleTemplateResolver.TemplateIssue(IssueKind.BLANK)), ModuleTemplateResolver.issues("  "))
    }

    @Test
    fun testUnbalancedBracesAreReported() {
        assertEquals(listOf(IssueKind.UNBALANCED_BRACES), ModuleTemplateResolver.issues("{lang/{ns}.json").map { it.kind })
    }

    @Test
    fun testNestedBracesAreUnbalanced() {
        assertEquals(listOf(IssueKind.UNBALANCED_BRACES), ModuleTemplateResolver.issues("{{lang}}.json").map { it.kind })
    }

    @Test
    fun testUnknownPlaceholderIsReportedOnceWithItsName() {
        val issues = ModuleTemplateResolver.issues("{lang}/{domain}/{domain}.json")

        assertEquals(listOf(ModuleTemplateResolver.TemplateIssue(IssueKind.UNKNOWN_PLACEHOLDER, "domain")), issues)
    }

    @Test
    fun testATemplateWithoutALanguagePlaceholderIsReported() {
        assertEquals(
            listOf(IssueKind.NO_LANGUAGE_PLACEHOLDER),
            ModuleTemplateResolver.issues("src/locales/{ns}.json").map { it.kind }
        )
    }

    @Test
    fun testAValidTemplateHasNoIssue() {
        assertTrue(ModuleTemplateResolver.issues("src/locales/{lang}/{ns}.json").isEmpty())
    }

    @Test
    fun testRootStatusIsUnsetWhenNoRootDirectoryIsConfigured(@TempDir base: File) {
        assertEquals(RootStatus.UNSET, ModuleTemplateResolver.rootStatus(ModuleConfig(), base.path))
    }

    @Test
    fun testRootStatusIsUnsetWhenTheProjectDirectoryIsUnknown() {
        val module = ModuleConfig(rootDirectory = "src/locales")

        assertEquals(RootStatus.UNSET, ModuleTemplateResolver.rootStatus(module, null))
    }

    @Test
    fun testRootStatusReportsAMissingDirectory(@TempDir base: File) {
        val module = ModuleConfig(rootDirectory = "gone")

        assertEquals(RootStatus.MISSING, ModuleTemplateResolver.rootStatus(module, base.path))
    }

    @Test
    fun testRootStatusReportsAnExistingDirectory(@TempDir base: File) {
        File(base, "locales").mkdirs()
        val module = ModuleConfig(rootDirectory = "locales")

        assertEquals(RootStatus.PRESENT, ModuleTemplateResolver.rootStatus(module, base.path))
    }

    @Test
    fun testDescribeFindsTheResolvedFile(@TempDir base: File) {
        File(base, "src/locales/fr").mkdirs()
        File(base, "src/locales/fr/common.json").writeText("{}")
        val module = ModuleConfig(rootDirectory = "src/locales", pathTemplate = "{lang}/{ns}.json")

        val resolution = ModuleTemplateResolver.describe(module, "fr", "common", base.path)

        assertEquals("src/locales/{lang}/{ns}.json", resolution.template)
        assertEquals("src/locales/fr/common.json", resolution.resolvedPath)
        assertTrue(resolution.exists)
        assertTrue(resolution.neighbours.isEmpty())
        assertTrue(resolution.issues.isEmpty())
    }

    @Test
    fun testDescribeListsTheNeighboursOfAMissingFile(@TempDir base: File) {
        File(base, "locales/fr").mkdirs()
        File(base, "locales/fr/translation.json").writeText("{}")
        File(base, "locales/fr/errors.json").writeText("{}")
        val module = ModuleConfig(rootDirectory = "locales", pathTemplate = "{lang}/{ns}.json")

        val resolution = ModuleTemplateResolver.describe(module, "fr", "common", base.path)

        assertFalse(resolution.exists)
        assertEquals(listOf("errors.json", "translation.json"), resolution.neighbours)
    }

    @Test
    fun testDescribeWithoutAProjectDirectoryPerformsNoLookup() {
        val module = ModuleConfig(rootDirectory = "locales", pathTemplate = "{lang}/{ns}.json")

        val resolution = ModuleTemplateResolver.describe(module, "fr", "common", null)

        assertEquals("locales/fr/common.json", resolution.resolvedPath)
        assertEquals(null, resolution.absolutePath)
        assertFalse(resolution.exists)
    }

    @Test
    fun testDescribeReportsTheIssuesOfTheCombinedTemplate() {
        val module = ModuleConfig(rootDirectory = "locales", pathTemplate = "{ns}.json")

        val resolution = ModuleTemplateResolver.describe(module, "fr", "common", null)

        assertEquals(listOf(IssueKind.NO_LANGUAGE_PLACEHOLDER), resolution.issues.map { it.kind })
    }
}
