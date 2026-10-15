package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.StaleTranslationInspection
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A translation reviewed on a reference text that changed since is reported in its file, and only
 * there: not once its value moved on, not without reviews, not in the reference locale.
 */
class StaleTranslationInspectionTest : PlatformBaseTest() {

    private val message = PluginBundle.message("inspection.stale.message", "en")

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply { reviewer = { "Alice <alice@example.com>" } }

    private fun root(): VirtualFile = ReviewLocation.rootOf(project, null)!!

    /** Reviews on for the project, `common:save` approved in [locale] on [value], against [source] in `en`. */
    private fun approve(locale: String, value: String, source: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            store().enable(root())
            store().decide(root(), locale, ReviewKey("common", "save"), ReviewState.APPROVED, value, source = ReviewSource.of("en", source))
        }
    }

    /** The warnings of the inspection on [path]. */
    private fun warnings(path: String): List<String> {
        myFixture.enableInspections(StaleTranslationInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it == message }
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
}
