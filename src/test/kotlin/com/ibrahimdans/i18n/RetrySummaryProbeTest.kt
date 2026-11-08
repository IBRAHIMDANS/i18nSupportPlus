package com.ibrahimdans.i18n

import org.junit.jupiter.api.Test
import java.io.File

// Temporary: fails on its first run and passes on the retry, to check the CI shard summary.
class RetrySummaryProbeTest {

    @Test
    fun failsOnceThenPasses() {
        val marker = File("build/retry-summary-probe.marker")
        if (!marker.exists()) {
            marker.parentFile.mkdirs()
            marker.writeText("first run")
            throw AssertionError("First run fails on purpose")
        }
    }
}
