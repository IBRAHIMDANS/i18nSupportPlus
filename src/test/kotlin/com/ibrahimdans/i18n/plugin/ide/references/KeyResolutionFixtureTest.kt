package com.ibrahimdans.i18n.plugin.ide.references

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.elementAt
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The hard cases of "which translation does this key point at?" in one fixture project
 * (`src/test/resources/keyResolution`), each run through the two paths that answer it:
 *
 * - **annotation**: an unknown key or namespace is reported where it is written — and only then;
 * - **reference**: Ctrl+click reaches the value, in the right file of the right module.
 *
 * Every other feature reading a key — completion, hints, inlays, folding, the gutter — goes through
 * the same resolution, so a case broken here is broken everywhere. A gap is a `@Disabled` test
 * asserting the behaviour we want, with the reason, so it documents the hole and turns green once fixed.
 *
 * Not covered: a same-named file outside the translations root. The light fixture's in-memory files
 * never sit under `project.basePath`, so `translationsRoot` cannot match through the project; that rule
 * is tested on its own in `LocalizationSourceServiceTest`.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeyResolutionFixtureTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/keyResolution"

    private val single = Config(defaultNs = "common", pluralSeparator = "_")

    // The fixture's files live under the in-memory root `/src`: `apps/web/…` is `src/apps/web/…`
    // to ModuleSources, so that is the module root to declare (as in UnusedKeysFixtureTest).
    private val monorepo = Config(
        defaultNs = "common",
        modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "src/apps/web"),
            ModuleConfig(name = "admin", rootDirectory = "src/apps/admin"),
            ModuleConfig(
                name = "docs", rootDirectory = "src/apps/docs",
                pathTemplate = "locales/{lang}/{ns}.json", keyTemplate = "{ns}.{key}"
            ),
        )
    )

    private val problems = setOf(
        PluginBundle.getMessage("annotator.unresolved.key"),
        PluginBundle.getMessage("annotator.unresolved.ns"),
        PluginBundle.getMessage("annotator.missing.default.ns"),
        PluginBundle.getMessage("annotator.object.reference"),
    )

    private fun withSingle(block: () -> Unit) {
        myFixture.copyDirectoryToProject("single", "")
        myFixture.runWithConfig(single, block)
    }

    private fun withMonorepo(block: () -> Unit) {
        myFixture.copyDirectoryToProject("monorepo", "")
        myFixture.runWithConfig(monorepo, block)
    }

    // ── The two paths ─────────────────────────────────────────────────────────

    /** The values Ctrl+click on [written] (quotes included) in [path] reaches, unquoted. */
    private fun targets(path: String, written: String): Set<String> = ReadAction.compute<Set<String>, RuntimeException> {
        val file = myFixture.psiManager.findFile(myFixture.findFileInTempDir(path))!!
        val element = myFixture.elementAt(file, written) ?: return@compute emptySet()
        listOfNotNull(element, element.parent)
            .flatMap { it.references.filterIsInstance<I18nReference>() }
            .flatMap { reference -> reference.multiResolve(false).mapNotNull { it.element?.text?.unQuote() } }
            .toSet()
    }

    /** The unresolved key or namespace problems reported in [path]. */
    private fun reported(path: String): List<String> {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it in problems }
    }

    private fun assertResolves(path: String, written: String, value: String) {
        Assertions.assertTrue(value in targets(path, written), "$written in $path should reach \"$value\", reached ${targets(path, written)}")
        Assertions.assertEquals(emptyList<String>(), reported(path), "$written in $path is reported")
    }

    // ── Keys that must resolve ────────────────────────────────────────────────

    @Test
    fun `an explicit namespace`() = withSingle {
        assertResolves("src/Explicit.tsx", "'common:title'", "Title")
    }

    @Test
    fun `every locale is reached`() = withSingle {
        Assertions.assertEquals(setOf("Title", "Titre"), targets("src/Explicit.tsx", "'common:title'"))
    }

    @Test
    fun `the default namespace`() = withSingle {
        assertResolves("src/Default.tsx", "'defaultOnly'", "Default only")
    }

    @Test
    fun `a nested key`() = withSingle {
        assertResolves("src/Nested.tsx", "'common:user.name'", "User name")
    }

    @Test
    fun `the namespace of a useTranslation hook`() = withSingle {
        assertResolves("src/Hook.tsx", "'dashboard.title'", "Dashboard")
    }

    @Test
    fun `a hook's key prefix`() = withSingle {
        assertResolves("src/Prefix.tsx", "'name'", "Profile name")
    }

    /** next-intl Server Components: `await getTranslations('ns')` prefixes its `t` like `useTranslations`. */
    @Test
    fun `the namespace of an awaited getTranslations`() = withSingle {
        assertResolves("src/ServerPrefix.tsx", "'name'", "Profile name")
    }

    @Test
    fun `the namespace option of getTranslations`() = withSingle {
        assertResolves("src/ServerNamespaceOption.tsx", "'name'", "User name")
    }

    @Test
    fun `the namespace of a getTranslations that is not awaited`() = withSingle {
        assertResolves("src/ServerNoAwait.tsx", "'name'", "Profile name")
    }

    @Test
    fun `a getTranslations without a namespace uses the default one`() = withSingle {
        assertResolves("src/ServerDefault.tsx", "'defaultOnly'", "Default only")
    }

    @Test
    fun `an ns option on the call`() = withSingle {
        assertResolves("src/NsOption.tsx", "'label'", "Other label")
    }

    @Test
    fun `a plural key reaches its forms`() = withSingle {
        val reached = targets("src/Plural.tsx", "'common:item'")
        Assertions.assertTrue(reached.any { "item" in it }, "reached $reached")
        Assertions.assertEquals(emptyList<String>(), reported("src/Plural.tsx"))
    }

    @Test
    fun `the i18nKey of a Trans component`() = withSingle {
        assertResolves("src/Trans.tsx", "\"common:title\"", "Title")
    }

    // ── Keys that must be reported ────────────────────────────────────────────

    @Test
    fun `an unknown key is reported`() = withSingle {
        Assertions.assertEquals(listOf(PluginBundle.getMessage("annotator.unresolved.key")), reported("src/MissingKey.tsx"))
        Assertions.assertEquals(emptySet<String>(), targets("src/MissingKey.tsx", "'common:nope.missing'"))
    }

    @Test
    fun `an unknown namespace is reported`() = withSingle {
        Assertions.assertEquals(listOf(PluginBundle.getMessage("annotator.unresolved.ns")), reported("src/MissingNs.tsx"))
    }

    @Test
    fun `an unknown key in a Trans component is reported`() = withSingle {
        Assertions.assertEquals(listOf(PluginBundle.getMessage("annotator.unresolved.key")), reported("src/TransMissing.tsx"))
    }

    /** Only known at runtime: reporting it would be a false alarm on every such call. */
    @Test
    fun `a dynamic key is not reported`() = withSingle {
        Assertions.assertEquals(emptyList<String>(), reported("src/Dynamic.tsx"))
    }

    // ── Monorepo ──────────────────────────────────────────────────────────────

    @Test
    fun `each module resolves in its own files`() = withMonorepo {
        Assertions.assertEquals(setOf("Web title"), targets("apps/web/src/Title.tsx", "'common:title'"))
        Assertions.assertEquals(setOf("Admin title"), targets("apps/admin/src/Title.tsx", "'common:title'"))
    }

    @Test
    fun `a key only another module has is reported`() = withMonorepo {
        Assertions.assertEquals(listOf(PluginBundle.getMessage("annotator.unresolved.key")), reported("apps/web/src/Foreign.tsx"))
        Assertions.assertEquals(emptySet<String>(), targets("apps/web/src/Foreign.tsx", "'common:adminOnly'"))
    }

    @Test
    fun `a module's key template`() = withMonorepo {
        assertResolves("apps/docs/src/Doc.tsx", "'guide.intro'", "Guide intro")
    }
}
