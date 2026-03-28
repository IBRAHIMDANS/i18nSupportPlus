package com.ibrahimdans.i18n.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.EnumSet

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

    // Override to suppress infra errors during the test body itself (JUnit 4 vintage path).
    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        var caught: Throwable? = null
        LoggedErrorProcessor.executeWith(suppressInfraErrors, ThrowableRunnable<RuntimeException> {
            try {
                super.runTestRunnable(testRunnable)
            } catch (t: Throwable) {
                caught = t
            }
        })
        caught?.let { throw it }
    }

    fun read(block: () -> Unit) = ApplicationManager.getApplication().runReadAction(block)
}
