package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.format.ParsedNode
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovery outside the IDE, on the example project the plugin is tried on by hand:
 * `examples/react-multi-namespace`, five namespaces in `public/locales/<locale>/`.
 */
class NioWorkspaceTest {

    private val example: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("examples/react-multi-namespace") }
        .first { Files.isDirectory(it) }

    private val namespaces = listOf("auth", "common", "dashboard", "deposit-box", "navigation")

    private fun discovery(config: Config = Config()) =
        SourceDiscovery(NioWorkspace(example), StaticI18nRegistry(ParsedFormat.entries), config)

    /** What the plugin's tests of the service look at: the path, the folder and the file name. */
    private fun TranslationSource<ParsedNode, ParsedFormat>.described() = Triple(displayPath, parent, name)

    private fun expected(locales: List<String> = listOf("en", "fr"), names: List<String> = namespaces) =
        locales.flatMap { locale -> names.map { Triple("public/locales/$locale/$it.json", locale, "$it.json") } }

    @Test
    fun theLocaleGuessFindsEveryTranslationFileAndNothingElse() {
        val sources = discovery().sources()

        assertEquals(expected(), sources.map { it.described() })
        assertTrue(sources.all { it.locale == null && it.namespace == null }, "no template states them")
        assertTrue(sources.all { it.localization == ParsedFormat.JSON })
    }

    @Test
    fun eachSourceIsReadByTheParser() {
        val common = discovery().sources(listOf("common")).single { it.parent == "fr" }

        assertEquals("Enregistrer", common.tree?.findChild("actions")?.findChild("save")?.value()?.value)
    }

    @Test
    fun theTranslationsRootFindsTheSameFiles() {
        assertEquals(expected(), discovery(Config(translationsRoot = "public/locales")).sources().map { it.described() })
        assertTrue(discovery(Config(translationsRoot = "src")).sources().isEmpty(), "no translation file under src")
    }

    @Test
    fun aNamespaceIsLookedUpByItsFileName() {
        assertEquals(expected(names = listOf("common")), discovery().sources(listOf("common")).map { it.described() })
        assertTrue(discovery().sources(listOf("missing")).isEmpty(), "an unknown namespace stays unresolved")
    }

    @Test
    fun aKeyWithoutNamespaceFallsBackToEverySourceWhenTheDefaultNamespaceHasNoFile() {
        assertEquals(expected(), discovery().sources(emptyList()).map { it.described() })
    }

    @Test
    fun aModuleTemplateStatesTheLocaleAndTheNamespace() {
        val module = ModuleConfig(name = "app", pathTemplate = "public/locales/{lang}/{ns}.json")

        val sources = discovery(Config(modules = listOf(module))).sources()

        assertEquals(expected(), sources.map { it.described() })
        assertEquals(
            listOf("en", "fr").flatMap { locale -> namespaces.map { locale to it } },
            sources.map { it.locale to it.namespace },
        )
    }

    @Test
    fun theWorkspaceIsTheExampleFolder() {
        val workspace = NioWorkspace(example)

        assertEquals(example.toAbsolutePath().normalize().toString().replace('\\', '/'), workspace.basePath)
        assertTrue(workspace.files(ParsedFormat.YAML).isEmpty(), "the example holds no YAML file")
        assertTrue(workspace.files(ParsedFormat.JSON).any { it.name == "package.json" }, "every JSON file is listed")
    }

    @Test
    fun theSourceFilesAreListedByExtension() {
        assertEquals(
            listOf("App.tsx", "Dashboard.tsx", "DepositBox.tsx", "LoginForm.tsx", "Navigation.tsx", "main.tsx"),
            NioWorkspace(example).files(listOf("tsx")).map { it.name }.sorted(),
        )
    }
}
