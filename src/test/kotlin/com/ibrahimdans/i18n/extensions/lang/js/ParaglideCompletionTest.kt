package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * After Paraglide's `m.`, every message of the message files is offered with its value — compiled or
 * not — once, and a key that is no identifier is inserted as an index.
 */
class ParaglideCompletionTest : PlatformBaseTest() {

    private val config = Config(previewLocale = "en")

    private fun addMessages() {
        addFileToProject("messages/en.json", """{"hello": "Hello", "added_since_compile": "New", "user": {"profile": {"title": "Profile"}}}""")
        addFileToProject("messages/fr.json", """{"hello": "Bonjour"}""")
    }

    private fun lookups(code: String): List<String> {
        myFixture.configureByText("A.ts", code)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    private val header = "import { m } from './paraglide/messages.js';\n"

    @Test
    fun everyMessageIsOfferedCompiledOrNot() = myFixture.runWithConfig(config) {
        addMessages()
        val offered = lookups(header + "export const a = m.<caret>")
        assertTrue(offered.containsAll(listOf("hello", "added_since_compile", "user.profile.title")), "$offered")
    }

    /** The value of the preview locale shows next to the key. */
    @Test
    fun theValueIsShown() = myFixture.runWithConfig(config) {
        addMessages()
        lookups(header + "export const a = m.<caret>")
        val hello = myFixture.lookupElements!!.first { it.lookupString == "hello" }
        val presentation = LookupElementPresentation().also(hello::renderElement)
        assertEquals("Hello", presentation.typeText)
    }

    /** The function Paraglide generated for `hello` is not offered a second time. */
    @Test
    fun aGeneratedFunctionIsNotADuplicate() = myFixture.runWithConfig(config) {
        addMessages()
        addFileToProject("paraglide/messages.js", "export const m = { hello: () => 'Hello' };")
        val offered = lookups("import { m } from './paraglide/messages.js';\nexport const a = m.<caret>")
        assertEquals(1, offered.count { it == "hello" }, "$offered")
    }

    @Test
    fun aKeyThatIsNoIdentifierIsInsertedAsAnIndex() = myFixture.runWithConfig(config) {
        addMessages()
        lookups(header + "export const a = m.<caret>")
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "user.profile.title" }
        myFixture.finishLookup('\n')
        assertTrue(myFixture.editor.document.text.endsWith("export const a = m[\"user.profile.title\"]"), myFixture.editor.document.text)
    }

    @Test
    fun anMOfAnotherModuleGetsNoMessage() = myFixture.runWithConfig(config) {
        addMessages()
        val offered = lookups("import { m } from './math';\nexport const a = m.<caret>")
        assertTrue("added_since_compile" !in offered, "$offered")
    }
}
