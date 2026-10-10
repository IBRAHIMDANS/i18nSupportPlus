package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TableViewModel
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationRow
import com.ibrahimdans.i18n.plugin.ide.toolwindow.UsageStatus
import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A key the project keeps is used, although no code names it: neither *Unused translation key*
 * nor the orphan scan behind *Scan Orphans* and *Cleanup Unused Keys* may report it.
 */
class KeptKeyUsageTest : PlatformBaseTest() {

    private companion object {
        const val UNUSED_MSG = "No reference to this key found in code"
        const val CATALOG = """{"errors":{"timeout":"Timed out","offline":"Offline"},"menu":{"home":"Home"}}"""
    }

    private val viewModel = TableViewModel()

    private fun unusedKeys(): List<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject("locales/en/common.json", CATALOG)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting()
            .filter { it.description == UNUSED_MSG }
            .map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }
    }

    @Test
    fun `the inspection does not report a kept key`() = myFixture.runWithConfig(Config(keptKeys = "common:errors.*")) {
        assertEquals(listOf("\"home\""), unusedKeys())
    }

    @Test
    fun `without a keep list every unnamed key is reported`() = myFixture.runWithConfig(Config()) {
        assertEquals(3, unusedKeys().size)
    }

    @Test
    fun `a kept key is neither an orphan nor a cleanup candidate`() = myFixture.runWithConfig(Config(keptKeys = "errors.timeout")) {
        addFileToProject("locales/en/common.json", CATALOG)
        val rows = listOf(
            TranslationRow("common:errors.timeout", mapOf("en" to "Timed out")),
            TranslationRow("common:menu.home", mapOf("en" to "Home")),
        )

        val scanned = viewModel.countUsages(project, rows)

        assertEquals(UsageStatus.KEPT, viewModel.usageStatus(scanned.first().usageCount))
        assertEquals(listOf("common:menu.home"), scanned.filter { it.usageCount == 0 }.map { it.key })
    }

    @Test
    fun `the quick fix adds the key to the keep list`() = myFixture.runWithConfig(Config(keptKeys = "errors.offline")) {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        val file = myFixture.addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("home"))

        myFixture.launchAction(myFixture.findSingleIntention("Keep 'common:menu.home'"))

        assertEquals("errors.offline, common:menu.home", Settings.getInstance(project).keptKeys)
        assertTrue(myFixture.doHighlighting().none { it.description == UNUSED_MSG })
    }
}
