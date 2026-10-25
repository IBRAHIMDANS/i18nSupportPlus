package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ToggleFoldingAction
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Switching *Show Translations Inline* in an editor that is already open: the keys fold there
 * and then, as in an editor opened with folding on, and nothing else of its folding moves.
 */
internal class FoldingToggleTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/folding"

    private val off = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = false)

    private val code = """
        export function test(i18n: {t: Function}) {
            return <div>{i18n.t("test:ref.section.key")}</div>;
        }
    """.trimIndent()

    private fun open(text: String) {
        myFixture.configureByFiles("assets/ru/test.json", "assets/en/test.json")
        myFixture.configureByText("app.tsx", text)
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
    }

    private fun keyRegion(): FoldRegion? =
        myFixture.editor.foldingModel.allFoldRegions.firstOrNull { it.placeholderText == "Translation test en" }

    private fun bodyRegion(): FoldRegion =
        myFixture.editor.foldingModel.allFoldRegions.first { it.placeholderText == "{...}" }

    /** Through the action, then the daemon's own pass, as in the IDE. */
    private fun toggle(state: Boolean) {
        val action = ToggleFoldingAction()
        action.setSelected(TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project)), state)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.doHighlighting()
    }

    @Test
    fun `turning folding on folds the keys of an open editor`() = myFixture.runWithConfig(off) {
        open(code)
        Assertions.assertNull(keyRegion(), "folding off: no region for the key")

        toggle(true)

        val region = keyRegion()
        Assertions.assertNotNull(region, "folding on: the key gets its region")
        Assertions.assertFalse(region!!.isExpanded, "folding on: the key is folded, as in a newly opened editor")
    }

    @Test
    fun `turning folding on leaves the rest of the folding as it was`() = myFixture.runWithConfig(off) {
        open(code)
        Assertions.assertTrue(bodyRegion().isExpanded)

        toggle(true)

        Assertions.assertTrue(bodyRegion().isExpanded, "a region that is not a key keeps its state")
    }

    @Test
    fun `the key under the caret stays open`() = myFixture.runWithConfig(off) {
        open(code.replace("ref.section.key", "ref.sec<caret>tion.key"))

        toggle(true)

        Assertions.assertTrue(keyRegion()!!.isExpanded, "the caret's key is left readable, as on opening")
    }

    @Test
    fun `turning folding off removes the key regions`() = myFixture.runWithConfig(off) {
        open(code)
        toggle(true)

        toggle(false)

        Assertions.assertNull(keyRegion(), "folding off: no region for the key any more")
    }
}
