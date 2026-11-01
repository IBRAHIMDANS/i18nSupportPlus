package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** [TableUsageScan] on its own: one count at a time, its result handed back, one retry after an interruption. */
class TableUsageScanTest : PlatformBaseTest() {

    private val rows = listOf(TranslationRow("common:save", mapOf("en" to "Save")))

    @Test
    fun `the counted rows are handed back and the scan can run again`() {
        var counted: List<TranslationRow>? = null
        val scan = TableUsageScan(project, { rows }, { it.map { row -> row.copy(usageCount = 2) } }) { counted = it }

        scan.start()
        pumpUntil { counted != null && !scan.isScanning }

        Assertions.assertEquals(2, counted?.single()?.usageCount)
        Assertions.assertFalse(scan.isScanning)
    }

    @Test
    fun `a start while a count runs is ignored`() {
        val calls = AtomicInteger()
        var counted: List<TranslationRow>? = null
        lateinit var scan: TableUsageScan
        scan = TableUsageScan(project, { rows }, { found ->
            calls.incrementAndGet()
            // Still counting: this start must not queue a second count.
            scan.start()
            found
        }) { counted = it }

        scan.start()
        pumpUntil { counted != null && !scan.isScanning }

        Assertions.assertEquals(1, calls.get())
    }

    @Test
    fun `an interrupted count runs again once`() {
        val calls = AtomicInteger()
        var counted: List<TranslationRow>? = null
        val scan = TableUsageScan(project, { rows }, { found ->
            if (calls.incrementAndGet() == 1) throw ProcessCanceledException()
            found
        }) { counted = it }

        scan.start()
        pumpUntil { counted != null && !scan.isScanning }

        Assertions.assertEquals(2, calls.get())
        Assertions.assertFalse(scan.isScanning)
    }

    /** Pumps the EDT, where the scan's callbacks run, until [done] holds or the time is up. */
    private fun pumpUntil(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
