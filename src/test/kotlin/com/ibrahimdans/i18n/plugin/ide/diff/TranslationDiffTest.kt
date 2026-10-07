package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChange.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationDiffTest {

    private fun file(locale: String, before: Map<List<String>, String>, after: Map<List<String>, String>) =
        FileVersions("common", locale, before, after)

    @Test
    fun `added, removed and modified keys are reported`() {
        val changes = TranslationDiff.changes(listOf(file(
            "en",
            mapOf(listOf("menu", "home") to "Home", listOf("menu", "old") to "Old", listOf("title") to "Title"),
            mapOf(listOf("menu", "home") to "Home page", listOf("menu", "new") to "New", listOf("title") to "Title"),
        )))
        assertEquals(
            listOf(
                Triple(listOf("menu", "home"), Kind.MODIFIED, "Home page"),
                Triple(listOf("menu", "new"), Kind.ADDED, "New"),
                Triple(listOf("menu", "old"), Kind.REMOVED, null),
            ),
            changes.map { Triple(it.path, it.kind, it.after) }
        )
    }

    @Test
    fun `reordering the keys changes nothing`() {
        val before = linkedMapOf(listOf("a") to "A", listOf("b") to "B")
        val after = linkedMapOf(listOf("b") to "B", listOf("a") to "A")
        assertTrue(TranslationDiff.changes(listOf(file("en", before, after))).isEmpty())
    }

    @Test
    fun `a flat key and a nested key stay apart`() {
        val changes = TranslationDiff.changes(listOf(file("en", mapOf(listOf("app.title") to "T"), mapOf(listOf("app", "title") to "T"))))
        assertEquals(setOf(Kind.ADDED, Kind.REMOVED), changes.map { it.kind }.toSet())
    }

    @Test
    fun `a locale that did not follow the reference is lagging`() {
        val changes = TranslationDiff.changes(listOf(
            file("en", mapOf(), mapOf(listOf("save") to "Save", listOf("exit") to "Exit")),
            file("fr", mapOf(), mapOf(listOf("save") to "Enregistrer")),
        ))
        val lagging = TranslationDiff.lagging(changes, "en", mapOf("common" to setOf("en", "fr", "de")), "-")
        assertEquals(
            listOf(listOf("save") to "de", listOf("exit") to "de", listOf("exit") to "fr").sortedBy { it.toString() },
            lagging.map { it.path to it.locale }.sortedBy { it.toString() }
        )
    }

    @Test
    fun `plural forms count as one key`() {
        val changes = TranslationDiff.changes(listOf(
            file("en", mapOf(), mapOf(listOf("item_one") to "1 item", listOf("item_other") to "n items")),
            file("ja", mapOf(), mapOf(listOf("item_other") to "n 個")),
        ))
        assertTrue(TranslationDiff.lagging(changes, "en", mapOf("common" to setOf("en", "ja")), "-").isEmpty())
    }

    @Test
    fun `a key removed from the reference leaves no locale behind`() {
        val changes = TranslationDiff.changes(listOf(file("en", mapOf(listOf("old") to "Old"), mapOf())))
        assertTrue(TranslationDiff.lagging(changes, "en", mapOf("common" to setOf("en", "fr")), "-").isEmpty())
    }
}
