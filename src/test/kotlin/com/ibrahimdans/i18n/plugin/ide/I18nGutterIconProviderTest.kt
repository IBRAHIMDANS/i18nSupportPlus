package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Gutter icons, and the fallback they used to carry on their own.
 *
 * This provider was the only consumer that worked around `findSources` returning nothing for a
 * key without a namespace: it substituted the default namespaces, then fell back to the whole
 * scan. #159 moved that fallback into `findSources` — confined to keys that request no
 * namespace — so the local one became *wider* than the shared rule rather than redundant, and
 * the gutter disagreed with the annotator on `t('common:user.name')` without `common.json`.
 */
class I18nGutterIconProviderTest : PlatformBaseTest() {

    private val cg = TsCodeGenerator()

    /**
     * Asks the provider itself rather than going through `findAllGutters()`, which collects the
     * markers of every registered provider and needs the Kotlin plugin to initialise — absent
     * from the test sandbox.
     */
    private fun gutterCount(file: PsiFile): Int = markers(file).size

    private fun markers(file: PsiFile, provider: I18nGutterIconProvider = I18nGutterIconProvider()): List<LineMarkerInfo<*>> {
        val markers = mutableListOf<LineMarkerInfo<*>>()
        ReadAction.run<RuntimeException> {
            file.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    provider.getLineMarkerInfo(element)?.let { markers += it }
                    super.visitElement(element)
                }
            })
        }
        return markers
    }

    private fun tooltip(file: PsiFile): String? = ReadAction.compute<String?, RuntimeException> {
        markers(file).single().lineMarkerTooltip
    }

    private fun addDashboardAndCommon() {
        addFileToProject("locales/en/dashboard.json", """{"stats": {"title": "Stats"}}""")
        addFileToProject("locales/fr/dashboard.json", """{"stats": {"title": "Statistiques"}}""")
        addFileToProject("locales/en/common.json", """{"shared": {"label": "Label"}}""")
        addFileToProject("locales/fr/common.json", """{"shared": {"label": "Libellé"}}""")
    }

    private fun twoNamespaceComponent(key: String): String = """
        import { useTranslation } from 'react-i18next';
        export default function Dashboard() {
            const { t } = useTranslation(['dashboard', 'common']);
            return t('$key');
        }
    """.trimIndent()

    /** The layout the local fallback existed for; it must keep working through the service. */
    @Test
    fun keyWithoutNamespaceStillGetsItsIcon() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/fr.json", """{"dashboard": {"title": "Ma pharmacie"}}""")
        addFileToProject("locales/en.json", """{"dashboard": {"title": "My pharmacy"}}""")
        val file = myFixture.configureByText("test.${cg.ext()}", cg.generate("\"dashboard.title\""))

        Assertions.assertEquals(1, gutterCount(file), "a resolvable key must carry a gutter icon")
    }

    /**
     * The disagreement this task removes: an explicit namespace matching no file is a
     * configuration error the annotator reports, so the gutter must not quietly compute
     * per-locale statuses against unrelated files.
     */
    @Test
    fun explicitNamespaceWithNoFileGetsNoIcon() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/fr.json", """{"dashboard": {"title": "Ma pharmacie"}}""")
        addFileToProject("locales/en.json", """{"dashboard": {"title": "My pharmacy"}}""")
        val file = myFixture.configureByText("test.${cg.ext()}", cg.generate("\"common:user.name\""))

        Assertions.assertEquals(
            0, gutterCount(file),
            "an unresolved namespace must not be papered over with the files that happen to exist"
        )
    }

    /** Anti-regression: a namespaced project keeps its icons, resolved through the file name. */
    @Test
    fun namespacedProjectKeepsItsIcon() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"user": {"name": "Name"}}""")
        val file = myFixture.configureByText("test.${cg.ext()}", cg.generate("\"common:user.name\""))

        Assertions.assertEquals(1, gutterCount(file), "a resolved namespace must still be marked")
    }

    /** The default namespace path, unaffected by the change. */
    @Test
    fun defaultNamespaceKeepsItsIcon() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("assets/translation.json", """{"app": {"title": "Titre"}}""")
        val file = myFixture.configureByText("test.${cg.ext()}", cg.generate("\"app.title\""))

        Assertions.assertEquals(1, gutterCount(file))
    }

    /**
     * A file declaring two namespaces yields one source per namespace per locale. The badge used
     * to count those sources, so a key translated in both locales of its namespace read 2/4.
     */
    @Test
    fun explicitNamespaceInTwoNamespaceFileCountsLocalesNotSources() = myFixture.runWithConfig(Config()) {
        addDashboardAndCommon()
        val file = myFixture.configureByText("Dashboard.tsx", twoNamespaceComponent("dashboard:stats.title"))

        val tooltip = tooltip(file)!!
        Assertions.assertTrue(tooltip.contains("All locales resolved (2/2)"), tooltip)
        Assertions.assertEquals(1, Regex(">en<").findAll(tooltip).count(), "each locale is listed once: $tooltip")
    }

    /** Without an explicit namespace, a locale is resolved as soon as one declared namespace holds the key. */
    @Test
    fun implicitNamespaceResolvesThroughAnyDeclaredNamespace() = myFixture.runWithConfig(Config()) {
        addDashboardAndCommon()
        val file = myFixture.configureByText("Dashboard.tsx", twoNamespaceComponent("shared.label"))

        val tooltip = tooltip(file)!!
        Assertions.assertTrue(tooltip.contains("All locales resolved (2/2)"), tooltip)
    }

    /**
     * The daemon replays the line-marker pass without any edit (focus, scroll, restart). The
     * former de-duplication cache lived until the next edit, so the second pass returned nothing
     * and the gutter emptied until the user typed.
     */
    @Test
    fun secondPassOnUnchangedDocumentKeepsItsMarkers() = myFixture.runWithConfig(Config()) {
        addDashboardAndCommon()
        val file = myFixture.configureByText("Dashboard.tsx", twoNamespaceComponent("dashboard:stats.title"))
        val provider = I18nGutterIconProvider()

        Assertions.assertEquals(1, markers(file, provider).size)
        Assertions.assertEquals(1, markers(file, provider).size, "an unchanged document must keep its badge")
    }

    /**
     * The provider is declared on JavaScript only: a .tsx inherits it through TypeScript JSX →
     * TypeScript → JavaScript. One declaration per dialect made the daemon collect it three times
     * for the same element, which is what the removed cache was papering over.
     */
    @Test
    fun providerIsRegisteredOncePerDialect() {
        for (ext in listOf("js", "jsx", "ts", "tsx")) {
            val language = myFixture.configureByText("dialect.$ext", "").language
            val count = LineMarkerProviders.getInstance().allForLanguage(language)
                .count { it is I18nGutterIconProvider }
            Assertions.assertEquals(1, count, ".$ext (${language.id}) must see the gutter provider exactly once")
        }
    }
}
