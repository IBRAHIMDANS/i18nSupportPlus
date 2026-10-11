package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The pure edits behind a rename, a move, a deletion and the cleanup of orphan reviews. */
class ReviewMigrationTest {

    private fun review(value: String) = Review(ReviewState.APPROVED, ReviewHash.of(value), "Alice", "2026-10-11T00:00:00Z")

    private val save = ReviewKey("common", "button.save")
    private val cancel = ReviewKey("common", "button.cancel")
    private val buttonsTitle = ReviewKey("common", "buttons.title")
    private val reviews = mapOf(save to review("Save"), cancel to review("Cancel"), buttonsTitle to review("Buttons"))

    @Test
    fun aLevelMovesEveryKeyBelowItButNotANamesake() {
        val moved = ReviewMigration.moved(reviews, listOf(ReviewKey("common", "button") to ReviewKey("common", "actions")), ".")

        Assertions.assertEquals(
            setOf(ReviewKey("common", "actions.save"), ReviewKey("common", "actions.cancel"), buttonsTitle),
            moved.keys,
            "`buttons.title` only starts with the same letters: it is not under `button`"
        )
        Assertions.assertEquals(reviews[save], moved[ReviewKey("common", "actions.save")], "the hash goes along: the approval holds")
    }

    @Test
    fun aKeyMovesToAnotherNamespace() {
        val moved = ReviewMigration.moved(reviews, listOf(save to ReviewKey("profile", "button.save")), ".")

        Assertions.assertNull(moved[save])
        Assertions.assertEquals(reviews[save], moved[ReviewKey("profile", "button.save")])
        Assertions.assertEquals(reviews[cancel], moved[cancel])
    }

    @Test
    fun movesReadTheReviewsAsTheyWereSoAChainMovesEachOnce() {
        val a = ReviewKey("ns", "a")
        val b = ReviewKey("ns", "b")
        val c = ReviewKey("ns", "c")
        val moved = ReviewMigration.moved(mapOf(a to review("A"), b to review("B")), listOf(a to b, b to c), ".")

        Assertions.assertEquals(review("A"), moved[b])
        Assertions.assertEquals(review("B"), moved[c])
        Assertions.assertNull(moved[a])
    }

    @Test
    fun flatKeysMoveOnlyThemselves() {
        val flat = mapOf(ReviewKey("ns", "app") to review("1"), ReviewKey("ns", "app.title") to review("2"))
        val moved = ReviewMigration.moved(flat, listOf(ReviewKey("ns", "app") to ReviewKey("ns", "web")), null)

        Assertions.assertEquals(setOf(ReviewKey("ns", "web"), ReviewKey("ns", "app.title")), moved.keys)
    }

    @Test
    fun removingAKeyRemovesTheKeysBelowItToo() {
        val left = ReviewMigration.removed(reviews, listOf(ReviewKey("common", "button")), ".")

        Assertions.assertEquals(setOf(buttonsTitle), left.keys)
    }

    @Test
    fun anOrphanIsAReviewWhoseKeyHasNoValueInItsLocale() {
        val translations = mapOf(
            "common:button.save" to mapOf("en" to "Save", "fr" to "Enregistrer"),
            "common:button.cancel" to mapOf("en" to "Cancel"),
            "menu.home" to mapOf("fr" to "Accueil"),
        )
        val fr = mapOf(save to review("Enregistrer"), cancel to review("Annuler"), ReviewKey("translation", "menu.home") to review("Accueil"))

        val orphans = ReviewMigration.orphans(mapOf("fr" to fr), translations, listOf("translation"))

        Assertions.assertEquals(listOf("fr" to cancel), orphans.map { it.locale to it.key })
    }

    @Test
    fun nothingIsAnOrphanWhenTheTranslationsLoadEmpty() {
        val fr = mapOf(save to review("Enregistrer"))

        Assertions.assertEquals(emptyList<Any>(), ReviewMigration.orphans(mapOf("fr" to fr), emptyMap(), listOf("translation")))
    }

    @Test
    fun nothingIsAnOrphanInALocaleTheLoadHoldsNothingFor() {
        val translations = mapOf("common:button.save" to mapOf("en" to "Save"))
        val de = mapOf(save to review("Speichern"))

        Assertions.assertEquals(emptyList<Any>(), ReviewMigration.orphans(mapOf("de" to de), translations, listOf("translation")))
    }
}
