package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `m.hello()` folds into the message, and a message with variants into its catch-all branch. */
class ParaglideFoldingTest : PlatformBaseTest() {

    private val config = Config(foldingPreferredLanguage = "en", foldingEnabled = true)

    private fun addMessages() {
        if (myFixture.tempDirFixture.getFile("messages/en.json") != null) return
        addFileToProject(
            "messages/en.json",
            """{"hello": "Hello", "user": {"profile": {"title": "Profile"}},
               "cart_items": [{"selectors": ["countPlural"], "match": {"countPlural=one": "One item", "countPlural=other": "{count} items"}}]}"""
        )
    }

    /** Each fold as the text it hides → the text it shows. */
    private fun folds(fileName: String, code: String): List<Pair<String, String>> {
        addMessages()
        val file = myFixture.configureByText(fileName, code)
        val text = myFixture.editor.document.text
        return ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(file, myFixture.editor.document, false)
                .map { text.substring(it.range.startOffset, it.range.endOffset) to (it.placeholderText ?: "") }
        }
    }

    private fun ts(vararg lines: String) = (listOf("import { m } from './paraglide/messages.js';") + lines).joinToString("\n")

    @Test
    fun aCallFoldsIntoItsMessage() = myFixture.runWithConfig(config) {
        assertEquals(
            listOf("m.hello()" to "Hello", "m[\"user.profile.title\"]()" to "Profile"),
            folds("A.ts", ts("export const a = [m.hello(), m[\"user.profile.title\"]()];"))
        )
    }

    /** Uncalled, the member folds alone, not the call it is passed to. */
    @Test
    fun anUncalledMemberFoldsAlone() = myFixture.runWithConfig(config) {
        assertEquals(listOf("m.hello" to "Hello"), folds("A.ts", ts("export const a = render(m.hello);")))
    }

    @Test
    fun variantsFoldIntoTheirCatchAllBranch() = myFixture.runWithConfig(config) {
        assertEquals(listOf("m.cart_items({ count: 2 })" to "{count} items…"), folds("A.ts", ts("export const a = m.cart_items({ count: 2 });")))
    }

    @Test
    fun aSvelteComponentFolds() = myFixture.runWithConfig(config) {
        val code = "<script>\n  import { m } from '\$lib/paraglide/messages.js';\n</script>\n<p>{m.hello()}</p>"
        assertEquals(listOf("m.hello()" to "Hello"), folds("Page.svelte", code))
    }

    @Test
    fun anUnknownMessageDoesNotFold() = myFixture.runWithConfig(config) {
        assertTrue(folds("A.ts", ts("export const a = m.missing();")).isEmpty())
    }

    @Test
    fun anotherPresetDoesNotFold() = myFixture.runWithConfig(
        config.copy(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        addMessages()
        val file = myFixture.addFileToProject("apps/web/A.ts", ts("export const a = m.hello();"))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val regions = ReadAction.compute<Int, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).size
        }
        assertEquals(0, regions)
    }

    /** Non-regression: an i18next key folds as before. */
    @Test
    fun anI18nextKeyStillFolds() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        assertEquals(listOf("t('common:save')" to "Save"), folds("B.js", "t('common:save');"))
    }
}
