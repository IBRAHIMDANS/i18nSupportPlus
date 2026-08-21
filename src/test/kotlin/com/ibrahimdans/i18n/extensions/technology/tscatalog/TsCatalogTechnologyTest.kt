package com.ibrahimdans.i18n.extensions.technology.tscatalog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Detection rules of [TsCatalogTechnology].
 *
 * A React Native / Expo project keeps its catalog in a plain TypeScript object keyed by
 * locale. Nothing read that shape before, so every key of such a project was unresolved.
 * These tests pin what counts as a catalog and, just as importantly, what does not: the
 * rule accepts any object whose keys are all locale codes, and two-letter ISO codes
 * collide with ordinary words.
 */
class TsCatalogTechnologyTest : PlatformBaseTest() {

    private val technology = TsCatalogTechnology()

    private fun catalogOf(file: PsiFile): List<Pair<String, Any>> =
        ReadAction.compute<List<Pair<String, Any>>, RuntimeException> {
            technology.findLocaleCatalog(file).map { (locale, obj) -> locale to (obj as Any) }
        }

    @Test
    fun findsEveryLocaleOfAPlainCatalog() {
        val file = addFileToProject(
            "src/i18n/translations.ts",
            """
            export const translations = {
              fr: { common: { cancel: 'Annuler' }, dashboard: { title: 'Ma pharmacie' } },
              en: { common: { cancel: 'Cancel' }, dashboard: { title: 'My pharmacy' } },
            } as const;
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("fr", "en"), catalogOf(file).map { it.first })
    }

    @Test
    fun findsACatalogExportedAsDefault() {
        val file = addFileToProject(
            "src/i18n/messages.ts",
            """
            export default {
              'pt-BR': { hello: 'Olá' },
            };
            """.trimIndent()
        )

        Assertions.assertEquals(listOf("pt-BR"), catalogOf(file).map { it.first })
    }

    /**
     * i18next declares `resources: { en: { translation: {…} } }`, whose value also has locale
     * keys. Both technologies claiming it would list every namespace twice.
     */
    @Test
    fun ignoresAnI18nextConfig() {
        val file = addFileToProject(
            "src/i18n/i18n.ts",
            """
            import i18n from 'i18next';

            i18n.init({
              fallbackLng: 'en',
              resources: { en: { translation: { hello: 'Hello' } } },
            });
            """.trimIndent()
        )

        Assertions.assertTrue(catalogOf(file).isEmpty(), "the i18next shape belongs to I18NextTechnology")
    }

    /** `createIntl({ en: … })` and friends are their framework's business, not a bare catalog. */
    @Test
    fun ignoresAnObjectPassedAsACallArgument() {
        val file = addFileToProject(
            "src/i18n/locales.ts",
            """
            import { configure } from 'somewhere';

            configure({ en: { hello: 'Hello' } });
            """.trimIndent()
        )

        Assertions.assertTrue(catalogOf(file).isEmpty())
    }

    @Test
    fun ignoresAnObjectWhoseKeysAreNotAllLocales() {
        val file = addFileToProject(
            "src/i18n/translations.ts",
            """
            export const config = {
              en: { hello: 'Hello' },
              defaults: { hello: 'Hello' },
            };
            """.trimIndent()
        )

        Assertions.assertTrue(catalogOf(file).isEmpty())
    }

    /**
     * `{ it: 'x', is: 'y' }` has none but locale codes as keys — `it` is Italian, `is` is
     * Icelandic. Requiring every value to be an object is what keeps such a map out.
     */
    @Test
    fun ignoresLocaleShapedKeysMappingToLiterals() {
        val file = addFileToProject(
            "src/i18n/lang.ts",
            """
            export const labels = { it: 'Italiano', is: 'Íslenska' };
            """.trimIndent()
        )

        Assertions.assertTrue(catalogOf(file).isEmpty())
    }

    @Test
    fun ignoresAnEmptyObject() {
        val file = addFileToProject("src/i18n/translations.ts", "export const translations = {};")

        Assertions.assertTrue(catalogOf(file).isEmpty())
    }

    /** End of the chain: the catalog must reach the resolution path as one source per locale. */
    @Test
    fun exposesOneSourcePerLocale() = myFixture.runWithConfig(Config()) {
        val catalog = addFileToProject(
            "src/i18n/translations.ts",
            """
            export const translations = {
              fr: { dashboard: { title: 'Ma pharmacie' } },
              en: { dashboard: { title: 'My pharmacy' } },
            } as const;
            """.trimIndent()
        )

        val sources = ReadAction.compute<List<com.ibrahimdans.i18n.LocalizationSource>, RuntimeException> {
            technology.findSourcesByConfiguration(project)
        }

        Assertions.assertEquals(listOf("fr", "en"), sources.map { it.parent })
        // The fixture nests its content root under the project base path, so a hand-written
        // "src/i18n/…" would not be the project-relative path of the file it just created.
        // What this test pins is the `#locale` suffix, not the path calculation itself.
        val relativePath = projectRelativePath(catalog.virtualFile.path)
        Assertions.assertEquals(
            listOf("$relativePath#fr", "$relativePath#en"),
            sources.map { it.displayPath },
            "one file yields several sources, so the display path must stay unique per locale"
        )
        Assertions.assertNotNull(
            ReadAction.compute<Any?, RuntimeException> {
                sources.first().tree?.findChild("dashboard")?.findChild("title")
            },
            "the tree must be rooted on the locale object so nested keys resolve"
        )
    }

    /**
     * A catalog outside the conventional folders is reachable through the configured root.
     *
     * Checked on [TsCatalogTechnology.isCandidate] with an explicit base path rather than
     * through `findSourcesByConfiguration`: the light fixture keeps its files in an in-memory
     * VFS rooted at `/src`, while `project.basePath` is a real temp directory, so a configured
     * root — matched as `"$basePath/$root"` exactly as `LocalizationSourceService.isIncluded`
     * does — can never prefix them. Going through the project here would assert nothing.
     */
    @Test
    fun findsACatalogUnderTheConfiguredTranslationsRoot() {
        val inRoot = addFileToProject("content/copy.ts", CATALOG).virtualFile
        val outOfRoot = addFileToProject("elsewhere/copy.ts", CATALOG).virtualFile
        // The fixture's own content root, i.e. the parent of the paths passed above.
        val basePath = inRoot.parent.parent.path
        val config = Config(translationsRoot = "content")

        ReadAction.run<RuntimeException> {
            Assertions.assertTrue(
                technology.isCandidate(inRoot, project, config, basePath),
                "a catalog under the configured root is a candidate whatever its folder is named"
            )
            Assertions.assertFalse(
                technology.isCandidate(outOfRoot, project, config, basePath),
                "a configured root excludes everything outside it"
            )
        }
    }

    /** Same calculation the technology applies, so the fixture layout stays out of the assertions. */
    private fun projectRelativePath(path: String): String =
        pathToRoot(project.basePath ?: "", path).trim('/')

    private companion object {
        const val CATALOG = "export const copy = { en: { hello: 'Hello' } };"
    }
}
