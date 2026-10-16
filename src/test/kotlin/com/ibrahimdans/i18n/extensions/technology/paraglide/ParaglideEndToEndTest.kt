package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractAnswer
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionAction
import com.ibrahimdans.i18n.plugin.ide.hint.HintProvider
import com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.references.code.ParaglideGotoDeclarationHandler
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.search.PsiSearchHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every Paraglide feature on one real project: `examples/paraglide-js` with the output
 * `paraglide-js compile` 2.29 wrote (`src/paraglide/`, kept as test data), and a Svelte component.
 *
 * The other Paraglide tests write their generated code by hand; this one catches what the real
 * compiler's output changes — its `_index.js`, its quoted export aliases, its JSDoc types.
 */
class ParaglideEndToEndTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/paraglide"

    private val config = Config(previewLocale = "en", foldingPreferredLanguage = "en", foldingEnabled = true, partialTranslationInspectionEnabled = true)

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val partial = PluginBundle.getMessage("annotator.partially.translated")

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun open(path: String) {
        myFixture.copyDirectoryToProject("project", "")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
    }

    /** The problems reported in the open file, as description → underlined text. */
    private fun problems(): List<Pair<String, String>> {
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting().filter { it.description == unresolved || it.description == partial }
            .map { it.description to text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun theProjectIsDetectedFromItsInlangSettings() {
        myFixture.copyDirectoryToProject("project", "")
        val locales = ReadAction.compute<Set<String?>, RuntimeException> {
            ParaglideTechnology().findSourcesByConfiguration(project).map { it.locale }.toSet()
        }
        Assertions.assertEquals(setOf("en", "fr"), locales)
        Assertions.assertEquals("en", ParaglideTechnology.baseLocale(project))
    }

    @Test
    fun annotationsOnTheRealProject() = myFixture.runWithConfig(config) {
        open("src/main.ts")
        Assertions.assertEquals(setOf(unresolved to "does_not_exist", partial to "only_in_english"), problems().toSet())
    }

    @Test
    fun anMOfAnotherModuleIsLeftAlone() = myFixture.runWithConfig(config) {
        open("src/notParaglide.ts")
        Assertions.assertEquals(emptyList<Pair<String, String>>(), problems())
    }

    @Test
    fun aSvelteComponentIsAnnotated() = myFixture.runWithConfig(config) {
        open("src/routes/Page.svelte")
        Assertions.assertEquals(listOf(unresolved to "missing_in_svelte"), problems())
    }

    @Test
    fun foldingShowsValuesAndVariants() = myFixture.runWithConfig(config) {
        open("src/main.ts")
        val folded = ReadAction.compute<List<String>, RuntimeException> {
            JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).map { it.placeholderText ?: "" }
        }
        assertTrue("Hello World!" in folded, "$folded")
        // Its other branch, cut at the folding length: never the raw `match` JSON.
        assertTrue(folded.any { it.startsWith("There are {count}") } && folded.none { "match" in it }, "$folded")
    }

    @Test
    fun hoverListsEveryLocale() = myFixture.runWithConfig(config) {
        open("src/main.ts")
        val offset = myFixture.editor.document.text.indexOf("hello_world") + 2
        val doc = ReadAction.compute<String?, RuntimeException> { HintProvider().generateDoc(null, myFixture.file.findElementAt(offset)) }
        assertTrue(doc != null && doc.contains("Hello World!") && doc.contains("Bonjour le monde"), "$doc")
    }

    /** Ctrl+click opens the message, not `src/paraglide/messages/greeting.js`. */
    @Test
    fun ctrlClickOpensTheMessage() = myFixture.runWithConfig(config) {
        open("src/main.ts")
        val offset = myFixture.editor.document.text.indexOf("m.greeting") + 4
        val targets = ReadAction.compute<List<String>, RuntimeException> {
            ParaglideGotoDeclarationHandler().getGotoDeclarationTargets(myFixture.file.findElementAt(offset), offset, myFixture.editor)
                .orEmpty().map { "${it.containingFile.parent?.name}/${it.containingFile.name}" }
        }
        Assertions.assertEquals(listOf("messages/en.json"), targets)
    }

    /** Completion offers each message once, the compiled function of the same name dropped. */
    @Test
    fun completionOffersEachMessageOnce() = myFixture.runWithConfig(config) {
        myFixture.copyDirectoryToProject("project", "")
        myFixture.configureByText("Fresh.ts", "import { m } from './src/paraglide/messages.js';\nexport const x = m.<caret>")
        myFixture.complete(CompletionType.BASIC)
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue(offered.containsAll(listOf("hello_world", "cart_items", "user.profile.title")), "$offered")
        Assertions.assertEquals(1, offered.count { it == "hello_world" }, "$offered")
    }

    /** Every message is used by the example, through calls, a Map of uncalled members or an index. */
    @Test
    fun usagesCountTheCodeNotTheGeneratedFolder() = myFixture.runWithConfig(config) {
        open("messages/en.json")
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        val unused = myFixture.doHighlighting().filter { it.description == "No reference to this key found in code" }
        Assertions.assertEquals(emptyList<String>(), unused.map { myFixture.editor.document.text.substring(it.startOffset, it.endOffset) })
        val counts = ReadAction.compute<List<Int>, RuntimeException> {
            val settings = Settings.getInstance(project).config()
            listOf("order_paid", "only_in_english", "user.profile.title").map {
                IndirectKeyUsages.textCount(it, settings, settings.searchScope(project), PsiSearchHelper.getInstance(project))
            }
        }
        Assertions.assertEquals(listOf(1, 1, 1), counts)
    }

    /** *Extract I18n* writes the message call and imports the object once. */
    @Test
    fun extractionWritesAMessageCall() = myFixture.runWithConfig(
        // The preset the setup wizard gives a Paraglide module.
        config.copy(modules = listOf(ModuleConfig(name = "app", rootDirectory = "src", preset = "paraglide")))
    ) {
        myFixture.copyDirectoryToProject("project", "")
        // At the root, as the fixture's VFS puts the module's `src` there (see ExtractParaglideTest).
        myFixture.configureByText("Extracted.ts", "export const title = 'Welcome <caret>back';")
        // The extraction dialog cannot show headless: answered as ExtractionTestBase does.
        val opener = ExtractI18nIntentionAction.opener
        ExtractI18nIntentionAction.opener = { _, model, _ ->
            val sources = model.sources(model.initialNamespace)
            ExtractAnswer.Create(model.initialNamespace, "welcome_back", sources.associateWith { if (it == sources.first()) model.text else "" }, variables = model.variables)
        }
        try {
            myFixture.launchActionAndWait(myFixture.findSingleIntention("Extract i18n key"))
        } finally {
            ExtractI18nIntentionAction.opener = opener
        }
        val text = myFixture.editor.document.text
        assertTrue(text.contains("m.welcome_back()") && text.contains("from './src/paraglide/messages.js'"), text)
    }
}
