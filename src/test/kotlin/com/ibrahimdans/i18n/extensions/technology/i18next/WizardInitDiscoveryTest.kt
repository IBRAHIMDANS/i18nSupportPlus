package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.CodeSettingsReader
import com.ibrahimdans.i18n.plugin.ide.settings.I18nextCliConfig
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DumbModeTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The setup wizard read `loadPath`, `defaultNS` and `fallbackLng` only from a few fixed files
 * ([I18nextCliConfig.INIT_FILE_NAMES]): an init in `app/i18n/config.ts` prefilled nothing. It now
 * falls back on the one initialisation the project holds, found by what it calls.
 */
class WizardInitDiscoveryTest : PlatformBaseTest() {

    private fun init(defaultNs: String) =
        """
            import i18n from 'i18next';
            import Backend from 'i18next-http-backend';

            i18n.use(Backend).init({ fallbackLng: 'en', defaultNS: '$defaultNs', backend: { loadPath: '/locales/{{lng}}/{{ns}}.json' } });
            export default i18n;
        """.trimIndent()

    private fun base(): VirtualFile = myFixture.tempDirFixture.getFile("")!!

    private fun isDirectory(path: String): Boolean = base().findFileByRelativePath(path)?.isDirectory == true

    /** What the wizard reads once the fixed files said nothing. */
    private fun discovered(): I18nextCliConfig? =
        CodeSettingsReader.wizardCliConfigOf(project, base(), CodeSettingsReader.wizardInitFiles(project), ::isDirectory)

    private fun addCatalogue() {
        addFileToProject("public/locales/en/common.json", """{"save": "Save"}""")
    }

    @Test
    fun `an init outside the usual places gives the root, the default namespace and the reference locale`() {
        addCatalogue()
        addFileToProject("app/i18n/config.ts", init("common"))

        val config = discovered()

        assertEquals("app/i18n/config.ts", config?.file)
        assertEquals("public/locales", config?.translationsRoot)
        assertEquals("{lang}/{ns}.json", config?.pathTemplate)
        assertEquals("common", config?.defaultNs)
        assertEquals("en", config?.referenceLocale)
    }

    @Test
    fun `a fixed file is left to the fixed lookup`() {
        addCatalogue()
        addFileToProject("src/i18n.ts", init("common"))

        assertNull(discovered(), "src/i18n.ts is one of the wizard's files: read first, by name")
    }

    @Test
    fun `two initialisations, one per application, prefill nothing`() {
        addCatalogue()
        addFileToProject("apps/web/lib/i18n.ts", init("web"))
        addFileToProject("apps/admin/lib/i18n.ts", init("admin"))

        assertNull(discovered())
    }

    @Test
    fun `no init call states nothing`() {
        addCatalogue()
        addFileToProject("app/i18n/config.ts", "export const locales = ['en', 'fr'];")

        assertEquals(emptyList<VirtualFile>(), CodeSettingsReader.wizardInitFiles(project))
        assertNull(discovered())
    }

    @Test
    fun `nothing is searched while the indexes are built`() {
        addCatalogue()
        addFileToProject("app/i18n/config.ts", init("common"))

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(emptyList<VirtualFile>(), CodeSettingsReader.wizardInitFiles(project))
        }
    }
}
