package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.computeImportPlan
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.encode
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.parse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CsvTranslationCodecTest {

    private val translations = mapOf(
        "common:menu.home" to mapOf("en" to "Home", "fr" to "Accueil"),
        "common:menu.exit" to mapOf("en" to "Exit, now", "fr" to ""),
        "profile:bio" to mapOf("en" to "He said \"hi\"", "fr" to "Ligne 1\nLigne 2"),
    )

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun exportThenParseRoundTripsWithoutLoss() {
        val csv = encode(listOf("en", "fr"), translations)
        val records = parse(csv)

        assertEquals(listOf("key", "en", "fr"), records.first())
        assertEquals(4, records.size)
        assertEquals(listOf("common:menu.exit", "Exit, now", ""), records[1])
        assertEquals(listOf("common:menu.home", "Home", "Accueil"), records[2])
        assertEquals(listOf("profile:bio", "He said \"hi\"", "Ligne 1\nLigne 2"), records[3])
    }

    @Test
    fun escapesCommaQuoteAndNewline() {
        val csv = encode(listOf("en"), mapOf("k" to mapOf("en" to "a,b\"c\nd")))
        assertTrue(csv.contains("\"a,b\"\"c\nd\""))
    }

    @Test
    fun parseAcceptsUnixLineEndings() {
        val records = parse("key,en\nk1,v1\nk2,v2\n")
        assertEquals(3, records.size)
        assertEquals(listOf("k2", "v2"), records.last())
    }

    @Test
    fun parseRejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { parse("key,en\nk1,\"unterminated") }
        assertThrows(IllegalArgumentException::class.java) { parse("key,en\nk1,val\"ue") }
    }

    // ── Import plan ───────────────────────────────────────────────────────────

    @Test
    fun plansUpdatesAndCreationsSeparately() {
        val records = listOf(
            listOf("key", "en", "fr"),
            listOf("common:menu.home", "Home v2", "Accueil"),
            listOf("common:menu.exit", "Exit, now", "Quitter"),
        )
        val plan = computeImportPlan(translations, listOf("en", "fr"), records)

        assertEquals(2, plan.entries.size)
        val update = plan.entries.first { it.key == "common:menu.home" }
        assertEquals("Home v2", update.value)
        assertEquals(false, update.isCreation)
        val creation = plan.entries.first { it.key == "common:menu.exit" }
        assertEquals("fr", creation.locale)
        assertEquals(true, creation.isCreation)
    }

    @Test
    fun unknownKeysAreIgnoredAndReportedNeverCreated() {
        val records = listOf(
            listOf("key", "en"),
            listOf("typo:menu.hom", "Oops"),
        )
        val plan = computeImportPlan(translations, listOf("en", "fr"), records)
        assertTrue(plan.entries.isEmpty())
        assertEquals(listOf("typo:menu.hom"), plan.ignoredKeys)
    }

    @Test
    fun unknownLocaleColumnsAreIgnoredAndReported()  {
        val records = listOf(
            listOf("key", "en", "klingon"),
            listOf("common:menu.home", "Home", "tlhIngan"),
        )
        val plan = computeImportPlan(translations, listOf("en", "fr"), records)
        assertTrue(plan.entries.isEmpty())
        assertEquals(listOf("klingon"), plan.ignoredColumns)
    }

    @Test
    fun emptyCellsNeverEraseExistingTranslations() {
        val records = listOf(
            listOf("key", "en", "fr"),
            listOf("common:menu.home", "", ""),
        )
        val plan = computeImportPlan(translations, listOf("en", "fr"), records)
        assertTrue(plan.entries.isEmpty())
    }

    @Test
    fun identicalValuesAreNoOps() {
        val records = listOf(
            listOf("key", "en", "fr"),
            listOf("common:menu.home", "Home", "Accueil"),
        )
        val plan = computeImportPlan(translations, listOf("en", "fr"), records)
        assertTrue(plan.entries.isEmpty())
    }

    @Test
    fun rejectsHeaderWithoutKeyColumn() {
        assertThrows(IllegalArgumentException::class.java) {
            computeImportPlan(translations, listOf("en"), listOf(listOf("clef", "en")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            computeImportPlan(translations, listOf("en"), emptyList())
        }
    }
}
