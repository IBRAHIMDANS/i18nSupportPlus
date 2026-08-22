package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsCodeGenerator
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
    private fun gutterCount(file: PsiFile): Int {
        val provider = I18nGutterIconProvider()
        var markers = 0
        ReadAction.run<RuntimeException> {
            file.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (provider.getLineMarkerInfo(element) != null) markers++
                    super.visitElement(element)
                }
            })
        }
        return markers
    }

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
}
