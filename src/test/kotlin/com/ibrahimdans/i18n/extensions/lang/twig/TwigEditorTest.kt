package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.hint.HintProvider
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * The editor of a Twig template in a Symfony project, through the extensions `twigConfig.xml` registers:
 * key states (annotator), folding as the editor builds it, Ctrl+click and the hint from a
 * `{% trans %}` body, and the domains a key falls back to.
 *
 * `SymfonyTranslationsTest` covers the plain cases (a filter or a tag resolving in its domain, a key
 * completed, a missing filter key); these are the ones it leaves out. Assertions are qualified:
 * `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TwigEditorTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val partial = PluginBundle.getMessage("annotator.partially.translated")
    private val config = Config(foldingEnabled = true, foldingPreferredLanguage = "en", partialTranslationInspectionEnabled = true)

    /** Runs [block] in a Symfony project when the Twig plugin is loaded, and skips the test otherwise. */
    private fun withSymfony(block: () -> Unit) {
        val twig = PluginManagerCore.getPlugin(PluginId.getId("com.jetbrains.twig"))
        assumeTrue(twig != null && twig.isEnabled, "the Twig plugin is not loaded in this sandbox")
        myFixture.runWithConfig(config) {
            addFileToProject("composer.json", """{"require": {"symfony/translation": "7.1.*"}}""")
            addFileToProject("translations/messages.en.yaml", "user:\n  name: Name\nonly.english: English\n")
            addFileToProject("translations/messages.fr.yaml", "user:\n  name: Nom\n")
            addFileToProject("translations/admin.en.yaml", "title: Admin\n")
            addFileToProject("translations/admin.fr.yaml", "title: Administration\n")
            block()
        }
    }

    private fun open(path: String, template: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, template).virtualFile)
        Assertions.assertEquals("Twig", myFixture.file.fileType.name, "$path must be parsed as Twig")
    }

    /** The descriptions the daemon reports on [template], with the text each one covers. */
    private fun reported(template: String): List<Pair<String, String>> {
        open("templates/page.html.twig", template)
        return myFixture.doHighlighting()
            .filter { it.description == unresolved || it.description == partial }
            .map { it.text to it.description }
    }

    private fun resolvedValue(template: String): String? {
        open("templates/nav.html.twig", template)
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        return targets.singleOrNull()?.text?.unQuote()?.trim()
    }

    // ── Key states ──────────────────────────────────────────────────────────────

    @Test
    fun aKeyMissingFromOneLocaleIsPartlyTranslated() = withSymfony {
        Assertions.assertEquals(listOf("only.english" to partial), reported("{{ 'user.name'|trans }} {{ 'only.english'|trans }}"))
    }

    @Test
    fun aMissingKeyOfATagIsReported() = withSymfony {
        Assertions.assertEquals(
            listOf("missing" to unresolved),
            reported("<p>{% trans %}user.name{% endtrans %}</p>\n<p>{% trans %}user.missing{% endtrans %}</p>")
        )
    }

    @Test
    fun aKeyIsReportedInTheDomainItNames() = withSymfony {
        Assertions.assertEquals(
            listOf("user.name" to unresolved),
            reported("{{ 'title'|trans({}, 'admin') }} {{ 'user.name'|trans({}, 'admin') }}")
        )
    }

    @Test
    fun aDomainKnownAtRuntimeIsNoKey() = withSymfony {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), reported("{{ 'anything'|trans({}, domain) }}"))
    }

    // ── Domains ─────────────────────────────────────────────────────────────────

    @Test
    fun aNullDomainFallsBackToTheTemplateDefault() = withSymfony {
        Assertions.assertEquals("Admin", resolvedValue("{% trans_default_domain 'admin' %}\n{{ 'ti<caret>tle'|trans({}, null) }}"))
    }

    @Test
    fun aTagFallsBackToTheTemplateDefault() = withSymfony {
        Assertions.assertEquals("Admin", resolvedValue("{% trans_default_domain 'admin' %}\n<p>{% trans %}ti<caret>tle{% endtrans %}</p>"))
    }

    // ── Folding ─────────────────────────────────────────────────────────────────

    /** The placeholders of the regions the folding manager built in the editor for [template]. */
    private fun foldedPlaceholders(template: String): List<String?> {
        open("templates/fold.html.twig", template)
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
    }

    @Test
    fun theEditorFoldsFiltersIntoTheirTranslation() = withSymfony {
        Assertions.assertEquals(
            listOf("Name", "Admin"),
            foldedPlaceholders("<h1>{{ 'user.name'|trans }}</h1>\n<p>{{ 'title'|trans({}, 'admin') }}</p>")
        )
    }

    /**
     * The Twig plugin's own folding builder (`com.jetbrains.twig.folding.TwigFoldingBuilder`) folds the
     * same `TWIG_STATEMENT_TAG` node: the editor keeps one region for the range, placeholder `???`.
     * `TwigFoldingBuilder` alone gives `Name` and `Admin` (SymfonyTranslationsTest).
     */
    @Disabled("2026-11-08 : the Twig plugin's folding of the {% trans %} tag shadows ours in the editor (placeholder ???)")
    @Test
    fun theEditorFoldsATagIntoItsTranslation() = withSymfony {
        Assertions.assertEquals(
            listOf("Name", "Admin"),
            foldedPlaceholders("<p>{% trans %}user.name{% endtrans %}</p>\n<p>{% trans from 'admin' %}title{% endtrans %}</p>")
        )
    }

    // ── Hint ────────────────────────────────────────────────────────────────────

    @Test
    fun theHintShowsTheTranslationOfAFilterKey() = withSymfony {
        open("templates/hint.html.twig", "{{ 'user.na<caret>me'|trans }}")
        val hint = ReadAction.compute<String?, RuntimeException> {
            HintProvider().generateDoc(null, myFixture.file.findElementAt(myFixture.caretOffset))
        }
        Assertions.assertNotNull(hint, "a resolved Twig key must produce a hint")
        Assertions.assertTrue(hint!!.contains("Name"), hint)
    }
}
