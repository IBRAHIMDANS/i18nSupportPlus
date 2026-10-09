package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [TranslationFileScope.sourceOf] tells a file of no translation kind apart without the project-wide
 * scan, and keeps recognising every kind the scan reads — including a TS catalog, whose `.ts` file
 * type no localization declares.
 */
class TranslationFileScopeTest : PlatformBaseTest() {

    private fun sourceOf(file: PsiFile) = ReadAction.compute<Any?, RuntimeException> { TranslationFileScope.sourceOf(file) }

    private fun addCommon() = addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

    @Test
    fun aComponentIsNoSource() {
        addCommon()
        val component = addFileToProject("src/Home.tsx", "export const Home = () => t('common:menu.home');")

        assertNull(sourceOf(component))
    }

    @Test
    fun aJavaFileIsNoSource() {
        addCommon()

        assertNull(sourceOf(addFileToProject("src/Notes.java", "public class Notes {}")))
    }

    /** A JSON file the scan leaves out (no locale in its path) is still no source. */
    @Test
    fun aJsonFileOutsideTheScanIsNoSource() {
        addCommon()

        assertNull(sourceOf(addFileToProject("package.json", """{"name":"app"}""")))
    }

    @Test
    fun aJsonTranslationFileIsTheSourceTheScanReturns() {
        val file = addCommon()

        val source = sourceOf(file)
        val scanned = ReadAction.compute<Any?, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project).single()
        }
        assertSame(scanned, source, "callers compare sources by identity: the scan's instance must be returned")
    }

    @Test
    fun aYamlTranslationFileIsASource() {
        assertNotNull(sourceOf(addFileToProject("locales/en/common.yml", "menu:\n  home: Home\n")))
    }

    /** Declared by a technology, in a `.ts` file: the file type alone would have ruled it out. */
    @Test
    fun aTsCatalogIsASource() {
        val catalog = addFileToProject(
            "src/i18n/translations.ts",
            """
            export const translations = {
              fr: { common: { cancel: 'Annuler' } },
              en: { common: { cancel: 'Cancel' } },
            } as const;
            """.trimIndent()
        )

        assertNotNull(sourceOf(catalog))
    }
}
