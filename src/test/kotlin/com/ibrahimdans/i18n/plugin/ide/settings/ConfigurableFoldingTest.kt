package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.editor.FoldRegion
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Enable folding* on the settings page, validated with an editor already open: its keys fold or
 * unfold there and then, as with *Show Translations Inline*, and only when the box changed.
 */
internal class ConfigurableFoldingTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/folding"

    private val off = Config(foldingPreferredLanguage = "en", foldingMaxLength = 20, foldingEnabled = false)

    private fun open() {
        myFixture.configureByFiles("assets/ru/test.json", "assets/en/test.json")
        myFixture.configureByText(
            "app.tsx",
            """
                export function test(i18n: {t: Function}) {
                    return <div>{i18n.t("test:ref.section.key")}</div>;
                }
            """.trimIndent()
        )
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
    }

    private fun keyRegion(): FoldRegion? =
        myFixture.editor.foldingModel.allFoldRegions.firstOrNull { it.placeholderText == "Translation test en" }

    /** Opens the page, lets [edit] change the settings as the page does, then validates it. */
    private fun validate(edit: (Settings) -> Unit) {
        val page = Configurable(project)
        page.createComponent()
        edit(Settings.getInstance(project))
        page.apply()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.doHighlighting()
        page.disposeUIResources()
    }

    @Test
    fun `ticking Enable folding folds the keys of an open editor`() = myFixture.runWithConfig(off) {
        open()
        Assertions.assertNull(keyRegion(), "folding off: no region for the key")

        validate { it.foldingEnabled = true }

        val region = keyRegion()
        Assertions.assertNotNull(region, "folding on: the key gets its region")
        Assertions.assertFalse(region!!.isExpanded, "folding on: the key is folded at once")
    }

    @Test
    fun `unticking Enable folding removes the key regions`() = myFixture.runWithConfig(off) {
        open()
        validate { it.foldingEnabled = true }

        validate { it.foldingEnabled = false }

        Assertions.assertNull(keyRegion(), "folding off: no region for the key any more")
    }

    @Test
    fun `another setting validated leaves the folding as it was`() = myFixture.runWithConfig(off) {
        open()
        validate { it.foldingEnabled = true }
        val region = keyRegion()!!
        myFixture.editor.foldingModel.runBatchFoldingOperation { region.isExpanded = true }

        validate { it.foldingMaxLength = 30 }

        Assertions.assertTrue(keyRegion()!!.isExpanded, "folding unchanged: the key the user opened stays open")
    }
}
