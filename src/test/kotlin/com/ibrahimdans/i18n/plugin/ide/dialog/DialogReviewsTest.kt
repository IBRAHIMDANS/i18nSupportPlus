package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewState
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [DialogReviews] on its own, without a dialog around it: the badge it shows, and the marks it leaves
 * on machine proposals.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class DialogReviewsTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")
    private val originalRoot = TranslationDialog.reviewRoot

    @AfterEach
    fun restore() {
        TranslationDialog.reviewRoot = originalRoot
    }

    private fun store(): ReviewStore = ReviewStore.getInstance(project).apply {
        now = { Instant.parse("2026-10-11T14:02:11Z") }
        reviewer = { "Alice <alice@example.com>" }
    }

    /** A fixture directory standing for the module root; reviews on when [enabled]. */
    private fun reviewRoot(enabled: Boolean): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        if (enabled) WriteAction.runAndWait<RuntimeException> { root.createChildDirectory(this, ReviewLocation.DIRECTORY) }
        TranslationDialog.reviewRoot = { _, _ -> root.takeIf { store().isEnabled(it) } }
        return root
    }

    private fun reviews(): Pair<DialogReviews, Map<LocalizationSource, String?>> {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        addFileToProject("locales/fr/common.json", """{"greeting": "Bonjour"}""")
        val fullKey = KeysSynchronizer().buildFullKey("common:greeting", config)
        val viewModel = DialogViewModel(project)
        val sources = viewModel.loadTranslations(fullKey, null, null)
        return DialogReviews(project, fullKey, sources, viewModel, null, null) to sources
    }

    @Test
    fun `a module keeping no reviews shows no badge`() = myFixture.runWithConfig(config) {
        reviewRoot(enabled = false)
        val (reviews, sources) = reviews()
        Assertions.assertTrue(sources.keys.all { reviews.rootOf(it) == null })
        Assertions.assertNull(reviews.stateOf("fr"))
        Assertions.assertFalse(reviews.openOf("fr"))
    }

    @Test
    fun `a value never reviewed reads as such`() = myFixture.runWithConfig(config) {
        val root = reviewRoot(enabled = true)
        val (reviews, sources) = reviews()
        val fr = sources.keys.single { it.localeLabel() == "fr" }
        reviews.badgeOf(fr, root)
        Assertions.assertEquals(PluginBundle.message("review.dialog.state.none"), reviews.stateOf("fr"))
    }

    @Test
    fun `only a proposal written as proposed is marked to review`() = myFixture.runWithConfig(config) {
        val root = reviewRoot(enabled = true)
        val (reviews, sources) = reviews()
        val fullKey = KeysSynchronizer().buildFullKey("common:greeting", config)
        val en = sources.keys.single { it.localeLabel() == "en" }
        val fr = sources.keys.single { it.localeLabel() == "fr" }
        val proposals = mapOf(
            fr to TranslationDialog.MachineProposal("Bonjour", "machine:deepl"),
            en to TranslationDialog.MachineProposal("Hi", "machine:deepl"),
        )

        CommandProcessor.getInstance().executeCommand(project, {
            reviews.markMachineValues(fullKey, mapOf(fr to "Bonjour", en to "Hello there"), proposals)
        }, null, null)

        val key = ReviewKey("common", "greeting")
        Assertions.assertEquals(ReviewState.TO_REVIEW, store().reviews(root, "fr").getValue(key).state)
        Assertions.assertNull(store().reviews(root, "en")[key], "retouched by hand: not marked")
    }
}
