package com.ibrahimdans.i18n.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method
import java.util.EnumSet

// Suppress known IntelliJ infrastructure errors that are not plugin regressions.
// TestLoggerFactory converts ERROR-level logs into test failures, so we redirect them to LOG only.
// Active during setUp, tearDown, AND test body (see EdtTestInterceptor below).
//
// Known suppressions:
//   1. PHP plugin split-mode: InstanceAlreadyRegisteredException on duplicate service registration
//   2. JS stub index: js.global.symbol.index access during JSX/TSX indexing in tests
//   3. Index rebuild noise
//   4. Vue LSP (2025.3+): getPluginDistDirByClass() fails with lib/modules/ modular structure;
//      VueLspServerSupportProvider cannot be instantiated in the test sandbox
private val infraErrorSuppressor = object : LoggedErrorProcessor() {
    override fun processError(
        category: String,
        message: String,
        details: Array<String>,
        t: Throwable?
    ): Set<Action> {
        if (message.contains("is already registered")) return EnumSet.of(Action.LOG)
        if (message.contains("js.global.symbol.index") || message.contains("stub index")) return EnumSet.of(Action.LOG)
        if (message.contains("updateWithMap") || message.contains("Index IdIndex will be rebuilt")) return EnumSet.of(Action.LOG)
        if (message.contains("VueLspServerSupportProvider") || message.contains("VueLspTypeScriptService")) return EnumSet.of(Action.LOG)
        return super.processError(category, message, details, t)
    }
}

// Dispatches each test method (and @ParameterizedTest template) to the EDT so that
// IntelliJ PSI operations work correctly under the JUnit 5 engine.
// BasePlatformTestCase.runTest() normally handles this EDT dispatch for the vintage
// engine; this interceptor replicates that behaviour for JUnit 5.
// The infraErrorSuppressor is active for the duration of each test body so that
// background-thread infrastructure errors (e.g. Vue LSP init) don't fail tests.
private class EdtTestInterceptor : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) = dispatchOnEdt { invocation.proceed() }

    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) = dispatchOnEdt { invocation.proceed() }

    private fun dispatchOnEdt(block: () -> Unit) {
        var caught: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            LoggedErrorProcessor.executeWith(infraErrorSuppressor, ThrowableRunnable<RuntimeException> {
                try { block() } catch (t: Throwable) { caught = t }
            })
        }
        caught?.let { throw it }
    }
}

@ExtendWith(EdtTestInterceptor::class)
abstract class PlatformBaseTest: BasePlatformTestCase() {

    @BeforeEach
    fun setFixtureUp() {
        LoggedErrorProcessor.executeWith(infraErrorSuppressor, ThrowableRunnable<RuntimeException> {
            setUp()
        })
    }

    @AfterEach
    fun tearFixtureDown() {
        LoggedErrorProcessor.executeWith(infraErrorSuppressor, ThrowableRunnable<RuntimeException> {
            tearDown()
        })
    }

    fun read(block: () -> Unit) = ApplicationManager.getApplication().runReadAction(block)
}
