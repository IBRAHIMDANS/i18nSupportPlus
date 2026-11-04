package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportEntry
import com.ibrahimdans.i18n.plugin.utils.CsvTranslationCodec.ImportPlan
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which file the import writes a value to, and what its summary says.
 *
 * A key spelled without a namespace prefix was written to the first file of its locale, whatever
 * the namespace: with `common` as the default namespace, `menu.home` went into `en/auth.json`,
 * the first name in alphabetical order. And an entry no file could take was dropped without a
 * word while the summary counted it as imported.
 */
class ImportTranslationsActionTest {

    /** [parent] is the file's directory name alone, which is what the locale is read from. */
    private fun source(locale: String, name: String, parent: String = locale): LocalizationSource =
        mockk<LocalizationSource>(relaxed = true).also {
            every { it.name } returns name
            every { it.parent } returns parent
            every { it.displayPath } returns "locales/$parent/$name"
            // A relaxed mock answers "" for these, which reads as a locale a template stated.
            every { it.locale } returns null
            every { it.namespace } returns null
        }

    private val perNamespace = listOf(source("en", "auth.json"), source("en", "common.json"))

    private val commonByDefault = Config(defaultNs = "common")

    private fun entry(key: String, locale: String = "en", creation: Boolean = true) = ImportEntry(key, locale, "value of $key", creation)

    @Test
    fun `a key without a prefix goes to the default namespace's file, not to the first file of the locale`() {
        assertEquals("locales/en/common.json", translationFileFor("menu.home", "en", perNamespace, commonByDefault)?.displayPath)
    }

    @Test
    fun `a prefixed key goes to its namespace's file`() {
        assertEquals("locales/en/auth.json", translationFileFor("auth:login", "en", perNamespace, commonByDefault)?.displayPath)
    }

    @Test
    fun `a key of no default namespace file is skipped rather than written elsewhere`() {
        assertEquals(null, translationFileFor("menu.home", "en", perNamespace, Config(defaultNs = "translation")))
    }

    @Test
    fun `applyPlan counts the entries written and names the one no file takes`() {
        val plan = ImportPlan(
            listOf(entry("menu.home"), entry("auth:login", creation = false), entry("billing:total")),
            ignoredKeys = emptyList(), ignoredColumns = emptyList(),
        )

        val routed = ImportTranslationsAction.routePlan(plan, perNamespace, commonByDefault)

        assertEquals(
            listOf("menu.home" to "locales/en/common.json", "auth:login" to "locales/en/auth.json"),
            routed.writes.map { it.entry.key to it.source.displayPath },
        )
        assertEquals(listOf("billing:total"), routed.unrouted.map { it.key }, "no en/billing.json exists")
        val summary = ImportTranslationsAction.summaryOf(plan, routed).lines()
        assertEquals(PluginBundle.message("action.import.summary.applied", 2, 1, 1), summary[0], "two written, not three planned")
        assertTrue(summary[1].contains("billing:total (en)"), "the entry left out is named: ${summary[1]}")
    }

    @Test
    fun `a plan every file takes reports no entry left out`() {
        val plan = ImportPlan(listOf(entry("menu.home"), entry("auth:login")), ignoredKeys = listOf("typo.key"), ignoredColumns = emptyList())

        val summary = ImportTranslationsAction.summaryOf(plan, ImportTranslationsAction.routePlan(plan, perNamespace, commonByDefault)).lines()

        assertEquals(
            listOf(
                PluginBundle.message("action.import.summary.applied", 2, 2, 0),
                PluginBundle.message("action.import.summary.ignored.keys", 1, "typo.key"),
            ),
            summary,
        )
    }
}
