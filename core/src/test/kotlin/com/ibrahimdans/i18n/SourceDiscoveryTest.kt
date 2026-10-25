package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rules of [SourceDiscovery], on a folder written for each test: the cases the plugin's tests of
 * `LocalizationSourceService` check through the IDE (`LocalizationSourceServiceTest`,
 * `LocalizationSourceServiceRootTest`, `LocaleFileLayoutFallbackTest`, `IdeModuleSourcesTest`).
 */
class SourceDiscoveryTest {

    @TempDir
    lateinit var root: Path

    private fun file(path: String, text: String = """{"a": "A"}""") {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun discovery(
        config: Config = Config(),
        technologies: List<TranslationTechnology<ParsedNode, ParsedFormat>> = emptyList(),
    ) = SourceDiscovery(NioWorkspace(root), StaticI18nRegistry(ParsedFormat.entries, technologies), config)

    private fun List<TranslationSource<ParsedNode, ParsedFormat>>.paths() = map { it.displayPath }

    @Test
    fun aFileInALocaleFolderOrNamedAfterALocaleIsASource() {
        file("locales/en/common.json")
        file("locales/fr.yml", "a: A")
        file("config/settings.json")

        assertEquals(listOf("locales/en/common.json", "locales/fr.yml"), discovery().sources().paths())
    }

    @Test
    fun anAliasedFolderOrFileIsALocaleShownAsWritten() {
        file("locales/en/common.json")
        file("locales/gb/common.json")
        file("flat/en.json")
        file("flat/gb.json")

        assertEquals(listOf("flat/en.json", "locales/en/common.json"), discovery().sources().paths().sorted(), "no alias: gb is no locale")

        val sources = discovery(Config(localeAliases = "gb=en-GB")).sources()
        val gb = sources.filter { "gb" in it.displayPath }.associate { it.displayPath to (it.locale to it.namespace) }
        assertEquals(mapOf("flat/gb.json" to ("gb" to null), "locales/gb/common.json" to ("gb" to "common")), gb)
        assertTrue(sources.filter { "gb" in it.displayPath }.all { it.hasRecognizedLocale() && it.localeLabel() == "gb" })
    }

    @Test
    fun theFilesOfAModulePutAsideAreNoSource() {
        file("apps/web/locales/en.json")
        file("apps/admin/locales/en.json")
        file("apps/admin/tools/inner/locales/en.json")
        val config = Config(modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "apps/web"),
            ModuleConfig(name = "admin", rootDirectory = "apps/admin", enabled = false),
            ModuleConfig(name = "inner", rootDirectory = "apps/admin/tools/inner"),
        ))

        assertEquals(
            listOf("apps/admin/tools/inner/locales/en.json", "apps/web/locales/en.json"),
            discovery(config).sources().paths().sorted(),
            "an active module nested in one put aside keeps its own files",
        )
    }

    @Test
    fun aModulePutAsideDesignatesNoFileThroughItsTemplate() {
        file("apps/admin/messages/english.json")
        val module = ModuleConfig(name = "admin", rootDirectory = "apps/admin", pathTemplate = "messages/{lang}.json", enabled = false)

        assertEquals(emptyList<String>(), discovery(Config(modules = listOf(module))).sources().paths())
    }

    /** [root] with the files under `attached/` held as another project's, as the IDE sees an attached project. */
    private fun withAttachedProject(config: Config): SourceDiscovery<ParsedNode, ParsedFormat> {
        val nio = NioWorkspace(root)
        val workspace = object : SourceWorkspace<ParsedNode, ParsedFormat> by nio {
            override fun isForeign(file: WorkspaceFile) = "/attached/" in file.path
        }
        return SourceDiscovery(workspace, StaticI18nRegistry(ParsedFormat.entries), config)
    }

    @Test
    fun anAttachedProjectsFilesAreNoSourceOnceModulesDescribeTheProject() {
        file("webapp/locales/en.json")
        file("attached/webapp/locales/sv.json")
        file("shared/locales/de.json")
        val modules = Config(modules = listOf(ModuleConfig(name = "webapp", rootDirectory = "webapp")))

        assertEquals(
            listOf("shared/locales/de.json", "webapp/locales/en.json"),
            withAttachedProject(modules).sources().paths().sorted(),
            "the project's own files outside every module, a shared package, are still read",
        )
        assertEquals(3, withAttachedProject(Config()).sources().size, "without modules, nothing changes")
    }

    @Test
    fun yamlIsReadUnderBothExtensions() {
        file("locales/en.yaml", "menu:\n  home: Home")
        file("locales/fr.yml", "menu:\n  home: Accueil")

        val sources = discovery().sources()

        assertEquals(listOf(ParsedFormat.YAML, ParsedFormat.YAML), sources.map { it.localization })
        assertEquals(listOf("Home", "Accueil"), sources.map { it.tree?.findChild("menu")?.findChild("home")?.value()?.value })
    }

    @Test
    fun aFileThatDoesNotParseIsListedWithoutATree() {
        file("locales/en.json", """{"a": """)

        assertNull(discovery().sources().single().tree)
    }

    /** `vendor` is Composer's, but `lang/vendor/<package>/` holds a project's overrides of a package's translations. */
    @Test
    fun laravelPackageOverridesAreReadButNotComposerVendor() {
        file("lang/vendor/courier/en.json")
        file("resources/lang/vendor/courier/fr.json")
        file("vendor/acme/pkg/lang/en.json")
        file("node_modules/pkg/lang/en.json")

        assertEquals(
            listOf("lang/vendor/courier/en.json", "resources/lang/vendor/courier/fr.json"),
            discovery().sources().paths(),
        )
    }

    @Test
    fun theConfiguredExclusionsAddToTheDefaultOnes() {
        file("locales/en.json")
        file("legacy/locales/en.json")

        assertEquals(listOf("locales/en.json"), discovery(Config(excludedDirectories = "legacy, tmp")).sources().paths())
    }

    @Test
    fun isExcludedPathSparesOnlyAVendorDirectlyUnderLang() {
        val exclusions = setOf("vendor", "node_modules")
        fun excluded(path: String) = SourceDiscovery.isExcludedPath(path, exclusions)

        assertFalse(excluded("/app/lang/vendor/courier/en.json"))
        assertFalse(excluded("/app/resources/lang/vendor/courier/en.json"))
        assertTrue(excluded("/app/vendor/acme/pkg/lang/en.json"))
        assertTrue(excluded("/app/vendor/acme/pkg/lang/vendor/x/en.json"), "a lang/vendor inside Composer's vendor")
        assertTrue(excluded("/app/lang/vendor/node_modules/en.json"))
        assertTrue(excluded("/app/locales/vendor/en.json"), "only a lang parent spares vendor")
    }

    @Test
    fun theRootBoundsTheScan() {
        file("locales/en/common.json")
        file("locales2/en/common.json")
        file("public/locales/messages.json")

        assertEquals(listOf("locales/en/common.json"), discovery(Config(translationsRoot = "locales")).sources().paths())
        assertEquals(listOf("locales/en/common.json"), discovery(Config(translationsRoot = "locales/")).sources().paths())
        assertEquals(
            listOf("public/locales/messages.json"),
            discovery(Config(translationsRoot = "public/locales")).sources().paths(),
            "under a root, a file is a source whatever its name",
        )
    }

    @Test
    fun isIncludedGuessesALocaleWithoutARoot() {
        assertTrue(SourceDiscovery.isIncluded("/p/locales/en/common.json", "", "/p"))
        assertTrue(SourceDiscovery.isIncluded("/p/locales/fr.json", "  ", "/p"))
        assertFalse(SourceDiscovery.isIncluded("/p/config/settings.json", "", "/p"))
        assertTrue(SourceDiscovery.isUnderRoot("/p/locales", "locales", "/p"), "the root itself")
        assertFalse(SourceDiscovery.isUnderRoot("/p/locales2/en.json", "locales", "/p"))
    }

    /** Any `common.json` — a serverless config — was a source of `common`, shown as a locale named after the file. */
    @Test
    fun aNamespaceLookupIgnoresAFileOutsideTheRoot() {
        file("poc/serverless/common.json")

        assertEquals(listOf("poc/serverless/common.json"), discovery().sources(listOf("common")).paths())
        assertTrue(discovery(Config(translationsRoot = "public/locales")).sources(listOf("common")).isEmpty())
    }

    @Test
    fun aNamespaceLookupTakesTheFilesNamedAfterIt() {
        file("locales/en/common.json")
        file("locales/en/auth.json")

        assertEquals(listOf("locales/en/common.json"), discovery().sources(listOf("common")).paths())
    }

    /** `locales/fr.json` + `locales/en.json`: a key naming no namespace finds no `translation.json`, and reads every source. */
    @Test
    fun aKeyWithoutNamespaceFallsBackToTheWholeScan() {
        file("locales/fr.json")
        file("locales/en.json")

        assertEquals(listOf("en.json", "fr.json"), discovery().sources(emptyList()).map { it.name })
        assertTrue(discovery().sources(listOf("common")).isEmpty(), "an explicit namespace never falls back")
    }

    @Test
    fun theDefaultNamespaceKeepsPriorityOverTheFallback() {
        file("assets/translation.json")
        file("assets/other.json")

        assertEquals(listOf("translation.json"), discovery().sources(emptyList()).map { it.name })
    }

    /** A file whose names guess no locale is still found, with the locale the template states. */
    @Test
    fun aModuleTemplateDesignatesFilesTheGuessMisses() {
        file("apps/web/messages/english.json")
        val module = ModuleConfig(name = "web", rootDirectory = "apps/web", pathTemplate = "messages/{lang}.json")

        val source = discovery(Config(modules = listOf(module))).sources().single()

        assertEquals("apps/web/messages/english.json", source.displayPath)
        assertEquals("english", source.locale)
        assertNull(source.namespace)
    }

    /** A technology stating a file's namespace — a Transloco scope — lists it once, as it states it. */
    @Test
    fun aSourceATechnologyStatesIsListedOnce() {
        file("i18n/admin/en.json")
        val scope = TranslationSource<ParsedNode, ParsedFormat>(
            null, "en.json", "admin", "i18n/admin/en.json", ParsedFormat.JSON, namespace = "admin"
        )
        val technology = object : TranslationTechnology<ParsedNode, ParsedFormat> {
            override fun translationFunctionNames() = emptyList<String>()
            override fun findSourcesByConfiguration(workspace: Workspace) = listOf(scope)
            override fun initialize(workspace: Workspace) {}
            override fun cfgNamespaces() = emptyList<String>()
        }
        val discovery = discovery(technologies = listOf(technology))

        assertEquals(listOf(scope), discovery.sources())
        assertEquals(listOf(scope), discovery.sources(listOf("admin")), "found by the namespace it states")
        assertEquals(listOf(scope), discovery.namespaceFiles(listOf("admin")))
    }

    /** `'title' | transloco`: a key naming no namespace falls back to every source but a scope's. */
    @Test
    fun aKeyWithoutNamespaceNeverFallsBackToAFileATechnologyStatesTheNamespaceOf() {
        file("i18n/en.json")
        file("i18n/admin/en.json")
        val scope = TranslationSource<ParsedNode, ParsedFormat>(
            null, "en.json", "admin", "i18n/admin/en.json", ParsedFormat.JSON, namespace = "admin"
        )
        val technology = object : TranslationTechnology<ParsedNode, ParsedFormat> {
            override fun translationFunctionNames() = emptyList<String>()
            override fun findSourcesByConfiguration(workspace: Workspace) = listOf(scope)
            override fun initialize(workspace: Workspace) {}
            override fun cfgNamespaces() = emptyList<String>()
        }
        val discovery = discovery(technologies = listOf(technology))

        assertEquals(listOf("i18n/en.json"), discovery.sources(emptyList()).paths())
        assertEquals(listOf(scope), discovery.sources(listOf("admin")), "the scope is still read under its namespace")
    }

    @Test
    fun namespaceFilesKeepTheNamedListWhenNoTechnologyAddsToIt() {
        val named = listOf(TranslationSource<ParsedNode, ParsedFormat>(null, "common.json", "en", "en/common.json", ParsedFormat.JSON))

        assertSame(named, discovery().namespaceFiles(listOf("common"), named, emptyList()))
    }
}
