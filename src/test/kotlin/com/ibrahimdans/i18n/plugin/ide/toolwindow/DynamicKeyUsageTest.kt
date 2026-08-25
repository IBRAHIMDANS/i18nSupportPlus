package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A key reached only through a key the code builds at runtime is not an orphan.
 *
 * `t(`common:status.${'$'}{kind}`)` names none of the `status.*` keys, so the text scan finds
 * nothing and the *Usage* column read *Unused* — on keys *Cleanup unused keys*, which takes
 * its candidates from that same count, would then offer to delete.
 */
class DynamicKeyUsageTest : PlatformBaseTest() {

    private val viewModel = TableViewModel()

    private fun statusOf(key: String): UsageStatus {
        val scanned = viewModel.countUsages(project, listOf(TranslationRow(key, mapOf("en" to "value"))))
        return viewModel.usageStatus(scanned.first().usageCount)
    }

    private fun withDynamicCallSite() {
        addFileToProject(
            "locales/en/common.json",
            """{"status":{"pending":"Pending","accepted":"Accepted"},"menu":{"home":"Home"}}"""
        )
        addFileToProject(
            "src/Status.tsx",
            "export const label = (kind: string) => i18n.t(`common:status.\${kind}`);"
        )
    }

    @Test
    fun `every key under a dynamic head is reachable`() {
        withDynamicCallSite()

        assertEquals(UsageStatus.DYNAMIC, statusOf("common:status.pending"))
        assertEquals(UsageStatus.DYNAMIC, statusOf("common:status.accepted"))
    }

    @Test
    fun `a key outside the dynamic head stays an orphan`() {
        withDynamicCallSite()

        // A sibling of the dynamic head, not a child of it: nothing can reach it.
        assertEquals(UsageStatus.ORPHAN, statusOf("common:menu.home"))
    }

    @Test
    fun `a key named outright is counted, not reported as dynamic`() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        addFileToProject("src/Menu.tsx", "export const label = () => i18n.t('common:menu.home');")

        assertEquals(UsageStatus.USED, statusOf("common:menu.home"))
    }

    /**
     * The cleanup takes its candidates from this count, so a dynamically reached key must not
     * be among them. That is the whole protection: the guard the action used to carry never
     * fired, since `ReferencesSearch` on a JSON property does not match a reference resolved
     * onto that property's key literal.
     */
    @Test
    fun `a dynamically reached key is not a cleanup candidate`() {
        withDynamicCallSite()
        val rows = listOf(
            TranslationRow("common:status.pending", mapOf("en" to "Pending")),
            TranslationRow("common:menu.home", mapOf("en" to "Home")),
        )

        val candidates = viewModel.countUsages(project, rows).filter { it.usageCount == 0 }.map { it.key }

        assertEquals(listOf("common:menu.home"), candidates)
    }

    @Test
    fun `a project holding no dynamic key literal is unaffected`() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home","away":"Away"}}""")
        addFileToProject("src/Menu.tsx", "export const label = () => i18n.t('common:menu.home');")

        assertEquals(UsageStatus.ORPHAN, statusOf("common:menu.away"))
    }

    // ── The prefixes searched, without the platform ───────────────────────────

    @Test
    fun `a prefix is dropped one segment at a time`() {
        assertEquals(
            listOf("a:b.c", "a:b"),
            DynamicKeyUsages.prefixesOf("a:b.c.d", ":", ".")
        )
    }

    @Test
    fun `a prefix holding no separator is left out`() {
        // `status` alone would search a word far too common, and the head it finds says
        // nothing about which namespace it belongs to.
        assertEquals(listOf("common:status"), DynamicKeyUsages.prefixesOf("common:status.pending", ":", "."))
        assertEquals(listOf("a.b"), DynamicKeyUsages.prefixesOf("a.b.c", ":", "."))
        assertTrue(DynamicKeyUsages.prefixesOf("a.b", ":", ".").isEmpty())
        assertTrue(DynamicKeyUsages.prefixesOf("home", ":", ".").isEmpty())
    }

    @Test
    fun `keys sharing a head are searched once`() {
        val words = DynamicKeyUsages.searchWords(
            listOf("common:status.pending", "common:status.accepted", "common:status.declined"),
            ":", ".",
        )

        assertEquals(listOf("common:status"), words)
    }

    @Test
    fun `the configured separators are the ones used`() {
        assertEquals(listOf("a::b/c", "a::b"), DynamicKeyUsages.prefixesOf("a::b/c/d", "::", "/"))
    }
}
