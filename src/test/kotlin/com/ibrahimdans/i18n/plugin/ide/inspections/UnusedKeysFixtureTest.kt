package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * The hard cases of "is this key unused?" in one fixture project (`src/test/resources/unusedKeys`),
 * run through every path that answers it:
 *
 * - **inspection**: *Unused translation key*, which comes with a *Delete* quick fix;
 * - **scan**: the orphan scan behind *Scan Orphans* in the table, per module when modules exist;
 * - **cleanup**: the candidates *Cleanup Unused Keys* offers for deletion — the same scan over the
 *   whole project — and the deletion itself.
 *
 * Each case asserts both directions: the true orphans are reported, and the keys that are used
 * although no code names them outright are **never** offered for deletion. A gap is a `@Disabled`
 * test asserting the behaviour we want, with the reason, so it documents the hole and turns green
 * once fixed.
 */
class UnusedKeysFixtureTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/unusedKeys"

    private companion object {
        const val UNUSED_MSG = "Translation key is never used in code"

        val TRANSLATION_FILES = arrayOf("locales/en/common.json", "locales/en/admin.json", "locales/en/other.json")

        /** No code names them outright, and every one of them is used: deleting any breaks the app. */
        val NEVER_DELETED = setOf(
            "common:errors.timeout",    // t(`common:errors.${code}`)
            "common:errors.offline",
            "common:hook.title",        // useTranslation('common') + t('hook.title')
            "admin:dashboard.title",    // useTranslation(['admin', 'common']) + t('dashboard.title')
            "common:wrapped.plain",     // translate('…'), declared by a key assistance rule
            "common:wrapped.member",    // i18n.translate('…'), declared by a rule
            "common:api.rateLimited",   // kept by the project's keep list
        )

        /** Used through a hook's key prefix or an explicit `ns` option. */
        val NEVER_DELETED_INDIRECT = setOf(
            "common:profile.name",      // useTranslation('common', { keyPrefix: 'profile' }) + t('name')
            "other:label",              // t('label', { ns: 'other' })
        )

        /** Nothing uses them. `field.email` sits behind t(`${name}`): no static part, nothing to see. */
        val TRUE_ORPHANS = setOf("common:dead.key", "admin:dead.key", "other:dead", "common:field.email")
    }

    private val single = Config(
        keptKeys = "common:api.*",
        rules = listOf(
            EditorRuleState(trigger = "translate"),
            EditorRuleState(trigger = "i18n.translate"),
            EditorRuleState(trigger = "translate", exclude = true, constraintType = "filePath", matchMode = "regex", value = "legacy/"),
        ),
    )

    // The fixture's files live under the in-memory root `/src`, which their project path keeps:
    // `apps/web/…` is `src/apps/web/…` to ModuleSources, so that is the module root to declare.
    private val web = ModuleConfig(name = "web", rootDirectory = "src/apps/web")
    private val admin = ModuleConfig(name = "admin", rootDirectory = "src/apps/admin")
    private val monorepo = Config(modules = listOf(web, admin))

    private val viewModel = TableViewModel()

    // ── The three paths ───────────────────────────────────────────────────────

    private fun inspectionReports(vararg files: String): Set<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        return files.flatMapTo(sortedSetOf()) { path ->
            myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
            val unused = myFixture.doHighlighting().filter { it.description == UNUSED_MSG }
            ReadAction.compute<List<String>, RuntimeException> {
                val config = Settings.getInstance(project).config()
                unused.map { info ->
                    val property = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(info.startOffset), JsonProperty::class.java)!!
                    TranslationKeyUsages.keyOf(property.nameElement, config)
                }
            }
        }
    }

    private fun scanOrphans(module: ModuleConfig? = null): Set<String> =
        viewModel.countUsages(project, viewModel.loadRows(project, module))
            .filter { it.usageCount == 0 }
            .mapTo(sortedSetOf()) { it.key }

    /** What [CleanupUnusedKeysAction] offers: the scan over the whole project, modules or not. */
    private fun cleanupCandidates(): Set<String> = scanOrphans(module = null)

    private fun keysOnDisk(module: ModuleConfig? = null): Set<String> = viewModel.loadRows(project, module).mapTo(sortedSetOf()) { it.key }

    private fun withSingle(config: Config = single, block: () -> Unit) {
        myFixture.copyDirectoryToProject("single", "")
        myFixture.runWithConfig(config, block)
    }

    private fun withMonorepo(block: () -> Unit) {
        myFixture.copyDirectoryToProject("monorepo", "")
        myFixture.runWithConfig(monorepo, block)
    }

    // ── Keys that must never be offered for deletion ──────────────────────────

    @Test
    fun `scan - used keys no code names outright are never orphans`() = withSingle {
        val offered = scanOrphans().intersect(NEVER_DELETED + NEVER_DELETED_INDIRECT)
        assertTrue(offered.isEmpty(), "offered: $offered")
    }

    @Test
    fun `cleanup - used keys no code names outright are never candidates`() = withSingle {
        val offered = cleanupCandidates().intersect(NEVER_DELETED + NEVER_DELETED_INDIRECT)
        assertTrue(offered.isEmpty(), "offered: $offered")
    }

    @Test
    fun `inspection - used keys no code names outright are never reported`() = withSingle {
        val reported = inspectionReports(*TRANSLATION_FILES).intersect(NEVER_DELETED)
        assertTrue(reported.isEmpty(), "reported: $reported")
    }

    @Test
    fun `inspection - keys used through a key prefix or an ns option are never reported`() = withSingle {
        val reported = inspectionReports(*TRANSLATION_FILES).intersect(NEVER_DELETED_INDIRECT)
        assertTrue(reported.isEmpty(), "reported: $reported")
    }

    @Test
    fun `scan - a key under a dynamic namespace is never an orphan`() = withSingle {
        assertFalse("common:status.ok" in scanOrphans())
    }

    @Disabled("Gap #368: fallbackNS is not read; t('shared.ok') under useTranslation('admin') falls back to `common`, and `common:shared.ok` is offered for deletion")
    @Test
    fun `scan - a key reached through fallbackNS is never an orphan`() = withSingle {
        assertFalse("common:shared.ok" in scanOrphans())
    }

    /**
     * A call a rule excludes is not a translation call, so `common:wrapped.excluded` is unused in
     * principle. Every path still counts the literal in `legacy/Old.ts`: an over-approximation on
     * the safe side, asserted so a change in either direction is noticed.
     */
    @Test
    fun `a key passed to an excluded wrapper is still counted as used`() = withSingle {
        assertFalse("common:wrapped.excluded" in scanOrphans())
        assertFalse("common:wrapped.excluded" in inspectionReports(*TRANSLATION_FILES))
    }

    /** Without the keep list the same key is an orphan: the keep list is what protects it. */
    @Test
    fun `the keep list is what protects a key received from an API`() = withSingle(single.copy(keptKeys = "")) {
        assertTrue("common:api.rateLimited" in scanOrphans())
    }

    // ── Keys that must be reported ────────────────────────────────────────────

    @Test
    fun `scan - true orphans are reported`() = withSingle {
        val missing = TRUE_ORPHANS - scanOrphans()
        assertTrue(missing.isEmpty(), "missing: $missing")
    }

    @Test
    fun `inspection - a key behind a template with no static part is reported`() = withSingle {
        assertTrue("common:field.email" in inspectionReports(*TRANSLATION_FILES))
    }

    @Disabled("Gap #410: ReferencesSearch on a JSON property also returns the JSON plugin's JsonPropertyNameReference of every same-named property in any JSON file, so `dead.key` counts as used because admin.json holds a `dead.key` too — and in a project with two locales no key is ever reported")
    @Test
    fun `inspection - true orphans are reported`() = withSingle {
        val missing = TRUE_ORPHANS - inspectionReports(*TRANSLATION_FILES)
        assertTrue(missing.isEmpty(), "missing: $missing")
    }

    // ── The paths agree ───────────────────────────────────────────────────────

    @Test
    fun `scan and cleanup offer the same keys`() = withSingle {
        assertEquals(scanOrphans(), cleanupCandidates())
    }

    @Disabled("Gap #410: the inspection misses the orphans sharing a name with another JSON property")
    @Test
    fun `the inspection reports what the scan offers`() = withSingle {
        assertEquals(scanOrphans(), inspectionReports(*TRANSLATION_FILES))
    }

    /** The cleanup deletes what it offered, from every locale, and nothing else. */
    @Test
    fun `cleanup deletes its candidates and leaves every used key in place`() = withSingle {
        val candidates = cleanupCandidates()

        CleanupUnusedKeysAction().deleteKeys(project, candidates.toList())

        val left = keysOnDisk()
        assertTrue(left.intersect(candidates).isEmpty(), "not deleted: ${left.intersect(candidates)}")
        assertTrue(left.containsAll(NEVER_DELETED + NEVER_DELETED_INDIRECT), "deleted: ${(NEVER_DELETED + NEVER_DELETED_INDIRECT) - left}")
    }

    // ── Monorepo: two modules, one `common` namespace each ───────────────────

    @Test
    fun `monorepo - a key unused in its module is offered`() = withMonorepo {
        assertTrue("common:webDead" in scanOrphans(web))
        assertTrue("common:webDead" in cleanupCandidates())
        assertTrue("common:bothDead" in cleanupCandidates())
    }

    /**
     * `orphan` is unused in web but used in admin, under the same key. The usage search is not
     * scoped to a module, so neither path offers web's copy: missed, but on the safe side.
     */
    @Test
    fun `monorepo - a key another module uses under the same name is not offered`() = withMonorepo {
        assertFalse("common:orphan" in scanOrphans(web))
        assertFalse("common:orphan" in scanOrphans(admin))
        assertFalse("common:orphan" in cleanupCandidates())
    }

    @Test
    fun `monorepo - deleting an orphan of one module leaves the other module alone`() = withMonorepo {
        CleanupUnusedKeysAction().deleteKeys(project, listOf("common:webDead"))

        assertFalse("common:webDead" in keysOnDisk(web))
        assertEquals(setOf("common:adminOnly", "common:bothDead", "common:orphan", "common:title"), keysOnDisk(admin))
    }
}
