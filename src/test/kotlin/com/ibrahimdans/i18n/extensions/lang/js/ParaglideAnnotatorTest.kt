package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Paraglide JS names a message by a member of its compiled message object — `m.hello()` — with no
 * string anywhere. The key is read from that member once the file imports the object from
 * Paraglide, and annotated on the member's name.
 */
class ParaglideAnnotatorTest : PlatformBaseTest() {

    private val problems = setOf(
        PluginBundle.getMessage("annotator.unresolved.key"),
        PluginBundle.getMessage("annotator.partially.translated"),
        PluginBundle.getMessage("annotator.unresolved.ns"),
        PluginBundle.getMessage("annotator.object.reference"),
    )

    private fun addMessages() {
        if (myFixture.tempDirFixture.getFile("messages/en.json") != null) return
        myFixture.addFileToProject("messages/en.json", """{"hello": "Hello", "bye": "Bye", "user": {"profile": {"title": "Profile"}}}""")
        myFixture.addFileToProject("messages/fr.json", """{"hello": "Bonjour", "user": {"profile": {"title": "Profil"}}}""")
    }

    /** Each problem reported in [code], as its description and the text it underlines. */
    private fun reported(fileName: String, code: String): List<Pair<String, String>> {
        addMessages()
        myFixture.configureByText(fileName, code)
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting()
            .filter { it.description in problems }
            .map { it.description to text.substring(it.startOffset, it.endOffset) }
    }

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val partial = PluginBundle.getMessage("annotator.partially.translated")

    private fun ts(vararg lines: String) = (listOf("import { m } from './paraglide/messages.js';") + lines).joinToString("\n")

    @Test
    fun aResolvedMessageIsNotReported() {
        assertEquals(emptyList<Pair<String, String>>(), reported("A.ts", ts("export const a = m.hello();")))
    }

    @Test
    fun anUnknownMessageIsReportedOnItsName() {
        assertEquals(listOf(unresolved to "missing"), reported("A.ts", ts("export const a = m.missing();")))
    }

    /** Partial translation is reported only once its setting is on, as for any key. */
    @Test
    fun aMessageMissingInALocaleIsPartiallyTranslated() = myFixture.runWithConfig(Config(partialTranslationInspectionEnabled = true)) {
        assertEquals(listOf(partial to "bye"), reported("A.ts", ts("export const a = m.bye();")))
    }

    /** `m["a.b"]` is the nested key `a` → `b`, as Paraglide's nested-key plugin compiles it. */
    @Test
    fun anIndexedMemberReadsANestedKey() {
        assertEquals(emptyList<Pair<String, String>>(), reported("A.ts", ts("export const a = m[\"user.profile.title\"]();")))
        assertEquals(listOf(unresolved to "nope"), reported("B.ts", ts("export const b = m[\"user.profile.nope\"]();")))
    }

    /** Passed along without being called, the message is still named. */
    @Test
    fun aMemberNotCalledIsStillAKey() {
        assertEquals(listOf(unresolved to "missing"), reported("A.ts", ts("export const a = { greeting: m.missing };")))
    }

    @Test
    fun anAliasedImportIsRead() {
        val code = "import { m as msg } from '\$lib/paraglide/messages';\nexport const a = msg.missing();\nexport const b = m.other();"
        assertEquals(listOf(unresolved to "missing"), reported("A.ts", code))
    }

    /** Paraglide 1's form. */
    @Test
    fun aNamespaceImportIsRead() {
        val code = "import * as m from '@/paraglide/messages.js';\nexport const a = m.missing();"
        assertEquals(listOf(unresolved to "missing"), reported("A.ts", code))
    }

    @Test
    fun anMOfAnotherModuleIsNotAMessageObject() {
        val code = "import { m } from './math';\nexport const a = m.missing();\nexport const b = (obj) => obj.m.missing();"
        assertTrue(reported("A.ts", code).isEmpty())
    }

    @Test
    fun aComputedMemberIsNotAKey() {
        assertTrue(reported("A.ts", ts("export const a = (k: string) => m[k]();")).isEmpty())
    }

    @Test
    fun aSvelteComponentIsRead() {
        val code = """
            <script>
              import { m } from '${'$'}lib/paraglide/messages.js';
            </script>
            <p>{m.hello()}</p>
            <p>{m.missing()}</p>
        """.trimIndent()
        assertEquals(listOf(unresolved to "missing"), reported("Page.svelte", code))
    }

    /** A module whose preset names another framework does not read Paraglide's syntax. */
    @Test
    fun anI18nextPresetSilencesParaglide() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "i18next")))
    ) {
        addMessages()
        val file = myFixture.addFileToProject("apps/web/src/A.ts", ts("export const a = m.missing();"))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertTrue(myFixture.doHighlighting().none { it.description in problems })
    }
}
