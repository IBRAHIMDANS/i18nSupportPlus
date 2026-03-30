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

// Dispatches each test method (and @ParameterizedTest template) to the EDT so that
// IntelliJ PSI operations work correctly under the JUnit 5 engine.
// BasePlatformTestCase.runTest() normally handles this EDT dispatch for the vintage
// engine; this interceptor replicates that behaviour for JUnit 5.
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
            try { block() } catch (t: Throwable) { caught = t }
        }
        caught?.let { throw it }
    }
}

@ExtendWith(EdtTestInterceptor::class)
abstract class PlatformBaseTest: BasePlatformTestCase() {

    // Suppress known IntelliJ 2024.3 infrastructure errors that are not plugin regressions:
    // 1. PHP plugin split-mode service conflict: both Frontend and Backend variants of
    //    BasicPhpStubElementTypesSupplierService register, causing InstanceAlreadyRegisteredException.
    // 2. JS stub index errors: js.global.symbol.index access during JSX/TSX file indexing in tests.
    // TestLoggerFactory converts ERROR-level logs into test failures, so we redirect them to LOG only.
    private val suppressInfraErrors = object : LoggedErrorProcessor() {
        override fun processError(
            category: String,
            message: String,
            details: Array<String>,
            t: Throwable?
        ): Set<Action> {
            if (message.contains("is already registered")) {
                return EnumSet.of(Action.LOG)
            }
            if (message.contains("js.global.symbol.index") || message.contains("stub index")) {
                return EnumSet.of(Action.LOG)
            }
            if (message.contains("updateWithMap") || message.contains("Index IdIndex will be rebuilt")) {
                return EnumSet.of(Action.LOG)
            }
            return super.processError(category, message, details, t)
        }
    }

    @BeforeEach
    fun setFixtureUp() {
        LoggedErrorProcessor.executeWith(suppressInfraErrors, ThrowableRunnable<RuntimeException> {
            setUp()
        })
    }

    @AfterEach
    fun tearFixtureDown() {
        LoggedErrorProcessor.executeWith(suppressInfraErrors, ThrowableRunnable<RuntimeException> {
            tearDown()
        })
    }

    fun read(block: () -> Unit) = ApplicationManager.getApplication().runReadAction(block)
}
