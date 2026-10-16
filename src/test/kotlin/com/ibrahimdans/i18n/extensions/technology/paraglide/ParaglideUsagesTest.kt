package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.search.PsiSearchHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Paraglide key is used where the code writes `m.hello`: never seen before (the key is an
 * identifier, no string), while Paraglide's generated code, which names every message, made any
 * key look used. Both are read right now, by *Unused translation key* and the usages from the JSON.
 */
class ParaglideUsagesTest : PlatformBaseTest() {

    private val unusedMessage = "No reference to this key found in code"

    private fun addProject() {
        addFileToProject("messages/fr.json", """{"hello": "Bonjour", "unused": "Rien", "user": {"profile": {"title": "Profil"}, "gone": "Parti"}}""")
        // What `paraglide-js compile` writes: a runtime, the message object, one file per message.
        addFileToProject("src/paraglide/runtime.js", "export const getLocale = () => 'en';")
        addFileToProject("src/paraglide/messages.js", "export * from './messages/_index.js'\nexport * as m from './messages/_index.js'")
        addFileToProject(
            "src/paraglide/messages/_index.js",
            "export * from './hello.js'\nexport * from './unused.js'\nexport * from './user_profile_title.js'\nexport * from './user_gone.js'"
        )
        addFileToProject("src/paraglide/messages/hello.js", "export const hello = () => 'Hello';")
        addFileToProject("src/paraglide/messages/unused.js", "export const unused = () => 'Nothing';")
        addFileToProject(
            "src/paraglide/messages/user_profile_title.js",
            "const user_profile_title = () => 'Profile';\nexport { user_profile_title as \"user.profile.title\" };"
        )
        addFileToProject("src/paraglide/messages/user_gone.js", "const user_gone = () => 'Gone';\nexport { user_gone as \"user.gone\" };")
        addFileToProject(
            "src/routes/Page.svelte",
            "<script>\n  import { m } from '\$lib/paraglide/messages.js';\n</script>\n<p>{m.hello()}</p>\n<p>{m[\"user.profile.title\"]()}</p>"
        )
    }

    /** `hello` and `user.profile.title` are used in a Svelte component; `unused` and `user.gone` only named by the generated code. */
    @Test
    fun onlyTheKeysTheCodeWritesAreUsed() {
        addProject()
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("messages/fr.json"))
        val text = myFixture.editor.document.text
        val unused = myFixture.doHighlighting().filter { it.description == unusedMessage }
            .map { text.substring(it.startOffset, it.endOffset).trim('"') }
        assertEquals(setOf("unused", "gone"), unused.toSet())
    }

    /**
     * The call sites a key's text search finds — what the usage count and *Unused key* add up — are
     * the code's `m.hello`, never the generated `hello.js`, nor the alias `"user.profile.title"`.
     */
    @Test
    fun theUsagesOfAKeyAreItsCallSites() {
        addProject()
        val config = Settings.getInstance(project).config()
        val counts = ReadAction.compute<List<Int>, RuntimeException> {
            listOf("hello", "unused", "user.profile.title", "user.gone").map { key ->
                IndirectKeyUsages.textCount(key, config, config.searchScope(project), PsiSearchHelper.getInstance(project))
            }
        }
        assertEquals(listOf(1, 0, 1, 0), counts)
    }

    @Test
    fun theOutdirIsRecognised() {
        addProject()
        ReadAction.run<RuntimeException> {
            assertEquals(true, ParaglideOutdir.contains(myFixture.findFileInTempDir("src/paraglide/messages/hello.js")))
            assertEquals(true, ParaglideOutdir.contains(myFixture.findFileInTempDir("src/paraglide/messages.js")))
            assertEquals(false, ParaglideOutdir.contains(myFixture.findFileInTempDir("src/routes/Page.svelte")))
        }
    }
}
