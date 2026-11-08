package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.diff.TranslationChanges
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/** The action ships in `gitConfig.xml`, loaded only with the Git plugin. */
class CompareTranslationsWithBranchActionTest : PlatformBaseTest() {

    private val action get() = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.CompareTranslationsWithBranch")

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    @Test
    fun theActionIsRegisteredWithTheGitPlugin() {
        assertTrue(action is CompareTranslationsWithBranchAction)
    }

    /** A project with no Git repository has no branch to compare with. */
    @Test
    fun theActionIsHiddenWithoutAGitRepository() {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    @Test
    fun aBranchWithNoTranslationChangeSaysSo() {
        val shown = mutableListOf<String>()
        TestDialogManager.setTestDialog { message ->
            shown += message
            Messages.OK
        }

        val job = CompareTranslationsWithBranchAction.run(project, "main") { _, _ -> TranslationChanges(emptyList(), emptyList(), null) }
        PlatformTestUtil.waitWithEventsDispatching("the comparison never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf("The working tree changes no translation against main."), shown)
    }

    @Test
    fun aComparisonCancelledWhileItCollectsShowsNothing() {
        val started = CountDownLatch(1)
        // A collection that ends only once cancelled, checking for it as the Git diff does.
        fun endless(@Suppress("UNUSED_PARAMETER") project: Project, @Suppress("UNUSED_PARAMETER") branch: String): TranslationChanges {
            started.countDown()
            while (true) {
                ProgressManager.checkCanceled()
                Thread.onSpinWait()
            }
        }
        TestDialogManager.setTestDialog { message -> throw AssertionError("Unexpected modal: $message") }

        val job = CompareTranslationsWithBranchAction.run(project, "main", ::endless)
        PlatformTestUtil.waitWithEventsDispatching("the collection never started", { started.count == 0L }, 30)
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the cancelled collection never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(job.isCancelled, "the job ends cancelled")
    }
}
