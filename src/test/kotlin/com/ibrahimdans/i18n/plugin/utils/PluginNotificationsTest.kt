package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.CleanupUnusedKeysAction
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.actions.ModuleFocus
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.review.ui.CleanupReviewsAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An information fades out, a warning stays, and an action with nothing to do says so in a
 * notification: a modal shown on the way fails the test.
 */
class PluginNotificationsTest : PlatformBaseTest() {

    /** The notifications [run] posts, with every modal turned into a failure. */
    private fun notified(run: () -> Unit): List<Notification> {
        val seen = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                seen += notification
            }
        })
        val previous = TestDialogManager.setTestDialog(TestDialog { message -> throw AssertionError("Unexpected modal: $message") })
        try {
            run()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        return seen
    }

    @Test
    fun theResultsGroupFadesOutAndTheStickyOneStays() {
        val groups = NotificationGroupManager.getInstance()
        assertEquals(NotificationDisplayType.BALLOON, groups.getNotificationGroup(PluginNotifications.RESULTS).displayType)
        assertEquals(NotificationDisplayType.STICKY_BALLOON, groups.getNotificationGroup(PluginNotifications.STICKY).displayType)
    }

    @Test
    fun anInformationGoesToTheResultsGroupAndAWarningStays() {
        val shown = notified {
            PluginNotifications.notify(project, "done")
            PluginNotifications.notify(project, "skipped", NotificationType.WARNING)
        }
        assertEquals(listOf(PluginNotifications.RESULTS, PluginNotifications.STICKY), shown.map { it.groupId })
    }

    @Test
    fun syncWithNoTranslationFileSaysSoWithoutAModal() {
        val shown = notified { KeysSynchronizer().sync(project) }
        assertEquals(listOf(PluginBundle.message("action.sync.none.sources")), shown.map { it.content })
        assertEquals(PluginNotifications.RESULTS, shown.single().groupId)
    }

    @Test
    fun cleanupWithNoKeySaysSoWithoutAModal() {
        val action = CleanupUnusedKeysAction()
        val shown = notified {
            action.actionPerformed(TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project)))
        }
        assertEquals(listOf(PluginBundle.message("action.cleanup.none")), shown.map { it.content })
    }

    @Test
    fun cleanupReviewsWithNoReviewSaysSoWithoutAModal() {
        val action = CleanupReviewsAction()
        val shown = notified {
            action.actionPerformed(TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project)))
        }
        assertEquals(listOf(PluginBundle.message("action.cleanup.reviews.none")), shown.map { it.content })
    }

    /** Undo is kept by the Notifications tool window: the balloon may fade out. */
    @Test
    fun focusOnAModuleOffersUndoInAFadingBalloon() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web"), ModuleConfig(name = "admin", rootDirectory = "apps/admin")))
    ) {
        val shown = notified { ModuleFocus.focus(project, ModuleConfig(name = "admin", rootDirectory = "apps/admin")) }
        val notification = shown.single()
        assertEquals(PluginNotifications.RESULTS, notification.groupId)
        assertTrue(notification.actions.any { it.templateText == PluginBundle.message("modules.undo") })
    }
}
