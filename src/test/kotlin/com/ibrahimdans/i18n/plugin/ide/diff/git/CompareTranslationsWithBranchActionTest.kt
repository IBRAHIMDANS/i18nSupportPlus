package com.ibrahimdans.i18n.plugin.ide.diff.git

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The action ships in `gitConfig.xml`, loaded only with the Git plugin. */
class CompareTranslationsWithBranchActionTest : PlatformBaseTest() {

    private val action get() = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.CompareTranslationsWithBranch")

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
}
