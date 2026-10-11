package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.actions.ExportTranslationsAction.Companion.incomplete
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.computeImportPlan
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.encode
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.parse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExportMissingOnlyTest {

    private val locales = listOf("de", "en", "fr")

    private val translations = mapOf(
        "done" to mapOf("de" to "Fertig", "en" to "Done", "fr" to "Terminé"),
        "emptyFr" to mapOf("de" to "Speichern", "en" to "Save", "fr" to ""),
        "absentDe" to mapOf("en" to "Cancel", "fr" to "Annuler"),
        "absentEn" to mapOf("de" to "Nur Deutsch", "fr" to "Seulement français"),
    )

    @Test
    fun keepsOnlyTheKeysALocaleLacksOrLeavesEmpty() {
        assertEquals(setOf("emptyFr", "absentDe", "absentEn"), incomplete(locales, translations).keys)
    }

    @Test
    fun keepsEveryValueOfAKeptKeySoTheReferenceColumnStaysFilled() {
        assertEquals(mapOf("de" to "Speichern", "en" to "Save", "fr" to ""), incomplete(locales, translations)["emptyFr"])
    }

    @Test
    fun aCompleteProjectHasNothingToExport() {
        assertTrue(incomplete(locales, mapOf("done" to translations.getValue("done"))).isEmpty())
    }

    @Test
    fun theFullExportIsUnchanged() {
        val csv = encode(locales, translations)
        assertEquals(translations.size + 1, parse(csv).size)
    }

    @Test
    fun reImportingTheFilledFileWritesOnlyTheFilledCells() {
        val records = parse(encode(locales, incomplete(locales, translations)))
        // The translator fills fr for "emptyFr" and leaves the other gaps empty.
        val filled = records.map { row -> if (row.first() == "emptyFr") listOf("emptyFr", "Speichern", "Save", "Enregistrer") else row }

        val plan = computeImportPlan(translations, locales, filled)

        assertEquals(1, plan.entries.size)
        val entry = plan.entries.single()
        assertEquals(Triple("emptyFr", "fr", "Enregistrer"), Triple(entry.key, entry.locale, entry.value))
        assertTrue(entry.isCreation)
    }
}
