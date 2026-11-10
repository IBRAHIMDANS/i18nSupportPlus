package com.ibrahimdans.i18n.plugin.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import org.junit.jupiter.api.Assertions
import java.util.concurrent.TimeUnit

/**
 * [block] on a pooled thread, as an MCP tool plans a write before applying it on the EDT: a plan that
 * needed the EDT would fail here. Its exception — a refusal — is rethrown as it is.
 */
internal fun <T> offEdt(block: () -> T): T =
    // Caught on the pooled thread: the platform logs an exception escaping it as an error, which fails the test.
    ApplicationManager.getApplication().executeOnPooledThread<Result<T>> {
        runCatching {
            Assertions.assertFalse(ApplicationManager.getApplication().isDispatchThread, "planned off the EDT")
            block()
        }
    }.get(30, TimeUnit.SECONDS).getOrThrow()

/** [offEdt] under a read action, as the tools that read the translations at once plan. */
internal fun <T> readOffEdt(block: () -> T): T = offEdt { runReadAction(block) }
