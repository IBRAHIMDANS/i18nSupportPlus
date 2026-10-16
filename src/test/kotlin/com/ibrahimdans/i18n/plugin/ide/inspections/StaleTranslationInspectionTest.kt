package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.StaleTranslationInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A translation reviewed on a reference text that changed since is reported in its file, and only
 * there: not once its value moved on, not without reviews, not in the reference locale.
 */
class StaleTranslationInspectionTest : PlatformBaseTest() {

    private val message = PluginBundle.message("inspection.stale.message", "en")

    /** Module roots created on disk by the monorepo case, removed after each test. */
    private val diskRoots = mutableListOf<File>()

    @AfterEach
    fun deleteDiskRoots() {
        diskRoots.forEach { it.deleteRecursively() }
        LocalFileSystem.getInstance().refresh(false)
    }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }

    private fun root(): VirtualFile = ReviewLocation.rootOf(project, null)!!

    /** Reviews on for the project, `common:save` approved in [locale] on [value], against [source] in `en`. */
    private fun approve(locale: String, value: String, source: String) =
        approve(root(), locale, value, ReviewSource.of("en", source))

    /** Reviews on under [root], `common:save` approved in [locale] on [value], against [source]. */
    private fun approve(root: VirtualFile, locale: String, value: String, source: ReviewSource) {
        WriteCommandAction.runWriteCommandAction(project) {
            store().enable(root)
            store().decide(root, locale, ReviewKey("common", "save"), ReviewState.APPROVED, value, source = source)
        }
    }

    /** The warnings of the inspection on [path]: the stale ones, whatever their reference locale. */
    private fun warnings(path: String): List<String> {
        val stale = setOf("en", "de").map { PluginBundle.message("inspection.stale.message", it) }
        myFixture.enableInspections(StaleTranslationInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it in stale }
    }

    /** The text the stale warnings of [path] underline. */
    private fun underlined(path: String): List<String> {
        myFixture.enableInspections(StaleTranslationInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return myFixture.doHighlighting().filter { it.description == message }.map { it.text }
    }

    /**
     * The root directory a module holding [file] declares, as the plugin reads the file's path: a light
     * fixture prefixes the project-relative path, so it is taken from the source rather than written out.
     */
    private fun moduleRootOf(file: PsiFile, below: String): String {
        val source = project.service<LocalizationSourceService>().findAllSources(project)
            .single { it.tree?.value()?.containingFile?.virtualFile == file.virtualFile }
        return source.displayPath.removeSuffix("/$below").trim('/')
    }

    /**
     * [module]'s root as `ReviewLocation` resolves it — under the project's base path, on disk: a light
     * fixture keeps its files elsewhere, so the directory is created there for the reviews folder.
     */
    private fun diskRootOf(module: ModuleConfig): VirtualFile {
        val directory = File(project.basePath!!, module.rootDirectory).apply { mkdirs() }
        diskRoots += File(project.basePath!!, module.rootDirectory.substringBefore('/'))
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
    }

    @Test
    fun anApprovedValueWhoseSourceChangedIsReported() {
        addFileToProject("locales/en/common.json", """{"save": "Save and close", "cancel": "Cancel"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer", "cancel": "Annuler"}""")
        approve("fr", "Enregistrer", "Save")

        assertEquals(listOf(message), warnings("locales/fr/common.json"))
    }

    @Test
    fun anApprovedValueWhoseSourceIsUnchangedIsNotReported() {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        approve("fr", "Enregistrer", "Save")

        val warnings = warnings("locales/fr/common.json")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    /** A value edited since its review is to review anyway: changed since approved, not stale. */
    @Test
    fun aValueChangedSinceItsReviewIsNotReported() {
        addFileToProject("locales/en/common.json", """{"save": "Save and close"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer et fermer"}""")
        approve("fr", "Enregistrer", "Save")

        val warnings = warnings("locales/fr/common.json")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun nothingIsReportedWithoutReviews() {
        addFileToProject("locales/en/common.json", """{"save": "Save and close"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")

        val warnings = warnings("locales/fr/common.json")
        assertTrue(warnings.isEmpty(), "$warnings")
        assertTrue(ReviewLocation.directoryOf(root()) == null, "the inspection must not create the reviews folder")
    }

    @Test
    fun theReferenceLocaleIsNeverReported() {
        addFileToProject("locales/en/common.json", """{"save": "Save and close"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        approve("en", "Save and close", "Save")

        val warnings = warnings("locales/en/common.json")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    @Test
    fun aYamlValueWhoseSourceChangedIsUnderlined() {
        addFileToProject("locales/en/common.yaml", "save: Save and close\ncancel: Cancel\n")
        addFileToProject("locales/fr/common.yaml", "save: Enregistrer\ncancel: Annuler\n")
        approve("fr", "Enregistrer", "Save")

        assertEquals(listOf("Enregistrer"), underlined("locales/fr/common.yaml"))
    }

    @Test
    fun aYamlValueWhoseSourceIsUnchangedIsNotReported() {
        addFileToProject("locales/en/common.yaml", "save: Save\n")
        addFileToProject("locales/fr/common.yaml", "save: Enregistrer\n")
        approve("fr", "Enregistrer", "Save")

        val warnings = warnings("locales/fr/common.yaml")
        assertTrue(warnings.isEmpty(), "$warnings")
    }

    /**
     * Two applications, `web` translated from `en` and `admin` from `de`, each keeping its reviews in its
     * own folder, and `shop` without one. The same French value, approved in both folders against each
     * module's reference of the day, turned stale in both since: each file is compared with its own
     * module's reference and reviews — `admin`'s review, recorded against `de`, would never read stale
     * against `en` — and `shop` borrows no folder.
     */
    @Test
    fun eachModuleOfAMonorepoComparesWithItsOwnReferenceAndReviews() {
        val below = "locales/en/common.json"
        val web = addFileToProject("apps/web/$below", """{"save": "Save and close"}""")
        addFileToProject("apps/web/locales/fr/common.json", """{"save": "Enregistrer"}""")
        val admin = addFileToProject("apps/admin/$below", """{"save": "Save"}""")
        addFileToProject("apps/admin/locales/de/common.json", """{"save": "Speichern und schließen"}""")
        addFileToProject("apps/admin/locales/fr/common.json", """{"save": "Enregistrer"}""")
        val shop = addFileToProject("apps/shop/$below", """{"save": "Save and close"}""")
        addFileToProject("apps/shop/locales/fr/common.json", """{"save": "Enregistrer"}""")
        val webModule = ModuleConfig(name = "web", rootDirectory = moduleRootOf(web, below), referenceLocale = "en")
        val adminModule = ModuleConfig(name = "admin", rootDirectory = moduleRootOf(admin, below), referenceLocale = "de")
        val shopModule = ModuleConfig(name = "shop", rootDirectory = moduleRootOf(shop, below), referenceLocale = "en")

        myFixture.runWithConfig(Config(modules = listOf(webModule, adminModule, shopModule))) {
            approve(diskRootOf(webModule), "fr", "Enregistrer", ReviewSource.of("en", "Save"))
            approve(diskRootOf(adminModule), "fr", "Enregistrer", ReviewSource.of("de", "Speichern"))
            diskRootOf(shopModule)

            assertEquals(listOf(message), warnings("apps/web/locales/fr/common.json"))
            assertEquals(listOf(PluginBundle.message("inspection.stale.message", "de")), warnings("apps/admin/locales/fr/common.json"))
            val shopWarnings = warnings("apps/shop/locales/fr/common.json")
            assertTrue(shopWarnings.isEmpty(), "$shopWarnings")
        }
    }
}
