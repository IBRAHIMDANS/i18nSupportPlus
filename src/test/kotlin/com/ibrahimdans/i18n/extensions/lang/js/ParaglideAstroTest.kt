package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Paraglide's `m.hello()` inside an `.astro` component, in its two contexts: the `---`
 * frontmatter and a `{…}` expression of the template.
 *
 * As for `t('key')` (`AstroCallsTest`), nothing of ours targets Astro: the Astro plugin parses
 * both contexts into JavaScript PSI, and the message object is found by reading the imports of
 * the component's text. The Astro plugin is a test dependency only (`build.gradle.kts`).
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class ParaglideAstroTest : PlatformBaseTest() {

    private val config = Config(previewLocale = "en")

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private val problems = setOf(
        unresolved,
        PluginBundle.getMessage("annotator.partially.translated"),
        PluginBundle.getMessage("annotator.unresolved.ns"),
        PluginBundle.getMessage("annotator.object.reference"),
    )

    private val namedImport = "import { m } from '../paraglide/messages.js';"
    private val namespaceImport = "import * as m from '../paraglide/messages.js';"

    private fun addMessages() {
        if (myFixture.tempDirFixture.getFile("messages/en.json") != null) return
        addFileToProject("messages/en.json", """{"hello": "Hello", "bye": "Bye"}""")
        addFileToProject("messages/fr.json", """{"hello": "Bonjour", "bye": "Au revoir"}""")
    }

    private fun frontmatter(import: String, call: String) = "---\n$import\nconst title = $call;\n---\n<h1>{title}</h1>\n"

    private fun template(import: String, call: String) = "---\n$import\n---\n<h1>{$call}</h1>\n"

    /**
     * "No error is reported" is also true of a file nobody parsed: without this, a case would
     * pass with the Astro plugin absent and prove nothing.
     */
    private fun assertParsedAsAstro() =
        Assertions.assertEquals("Astro", myFixture.file.fileType.name, "the component must be parsed as Astro")

    /** Each problem reported in [code], as its description and the text it underlines. */
    private fun reported(fileName: String, code: String): List<Pair<String, String>> {
        addMessages()
        myFixture.configureByText(fileName, code)
        assertParsedAsAstro()
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting()
            .filter { it.description in problems }
            .map { it.description to text.substring(it.startOffset, it.endOffset) }
    }

    private fun lookups(fileName: String, code: String): List<String> {
        addMessages()
        myFixture.configureByText(fileName, code)
        assertParsedAsAstro()
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    // ── Annotation: a known message is silent, an unknown one is reported on its name ──

    @Test
    fun `a message in the frontmatter is resolved`() {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), reported("A.astro", frontmatter(namedImport, "m.hello()")))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), reported("B.astro", frontmatter(namespaceImport, "m.hello()")))
    }

    @Test
    fun `a message in a template expression is resolved`() {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), reported("A.astro", template(namedImport, "m.hello()")))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), reported("B.astro", template(namespaceImport, "m.hello()")))
    }

    @Test
    fun `an unknown message in the frontmatter is reported`() {
        val expected = listOf(unresolved to "does_not_exist")
        Assertions.assertEquals(expected, reported("A.astro", frontmatter(namedImport, "m.does_not_exist()")))
        Assertions.assertEquals(expected, reported("B.astro", frontmatter(namespaceImport, "m.does_not_exist()")))
    }

    @Test
    fun `an unknown message in a template expression is reported`() {
        val expected = listOf(unresolved to "does_not_exist")
        Assertions.assertEquals(expected, reported("A.astro", template(namedImport, "m.does_not_exist()")))
        Assertions.assertEquals(expected, reported("B.astro", template(namespaceImport, "m.does_not_exist()")))
    }

    @Test
    fun `an m of another module is left alone`() {
        Assertions.assertTrue(reported("A.astro", template("import { m } from '../math';", "m.does_not_exist()")).isEmpty())
    }

    // ── Completion after `m.` ─────────────────────────────────────────────────────

    @Test
    fun `messages are offered in the frontmatter`() = myFixture.runWithConfig(config) {
        val named = lookups("A.astro", frontmatter(namedImport, "m.<caret>"))
        Assertions.assertTrue(named.containsAll(listOf("hello", "bye")), "$named")
        val namespace = lookups("B.astro", frontmatter(namespaceImport, "m.<caret>"))
        Assertions.assertTrue(namespace.containsAll(listOf("hello", "bye")), "$namespace")
    }

    @Test
    fun `messages are offered in a template expression`() = myFixture.runWithConfig(config) {
        val named = lookups("A.astro", template(namedImport, "m.<caret>"))
        Assertions.assertTrue(named.containsAll(listOf("hello", "bye")), "$named")
        val namespace = lookups("B.astro", template(namespaceImport, "m.<caret>"))
        Assertions.assertTrue(namespace.containsAll(listOf("hello", "bye")), "$namespace")
    }

    // ── Navigation from the message file to its call sites ────────────────────────

    /** Ctrl+click on `"hello"` in a message file reaches `m.hello` in the component. */
    @Test
    fun `a message file navigates to its call sites in a component`() {
        addMessages()
        addFileToProject("src/components/Greeting.astro", template(namedImport, "m.hello()"))
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("messages/en.json"))
        val callSites = ReadAction.compute<List<String>, RuntimeException> {
            val offset = myFixture.editor.document.text.indexOf("hello") + 1
            val element = myFixture.file.findElementAt(offset)?.parent
            Assertions.assertNotNull(element, "no PSI element at the message's name")
            element!!.references
                .flatMap { ref -> (ref as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }.orEmpty() }
                .map { "${it.containingFile.name}: ${it.text}" }
        }
        Assertions.assertEquals(listOf("Greeting.astro: m.hello"), callSites)
    }
}
