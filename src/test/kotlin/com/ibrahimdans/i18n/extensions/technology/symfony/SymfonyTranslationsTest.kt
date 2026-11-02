package com.ibrahimdans.i18n.extensions.technology.symfony

import com.ibrahimdans.i18n.extensions.localization.xliff.XliffContentGenerator
import com.ibrahimdans.i18n.extensions.lang.twig.TwigFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Symfony's catalogues — `translations/<domain>.<locale>.<format>` — read from its PHP calls and its
 * Twig templates, the domain being the namespace the key is looked up in.
 */
class SymfonyTranslationsTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun symfonyProject() {
        myFixture.addFileToProject("composer.json", """{"require": {"php": ">=8.2", "symfony/framework-bundle": "7.1.*", "symfony/translation": "7.1.*"}}""")
        myFixture.addFileToProject("translations/messages.en.yaml", "user:\n  name: Name\nflat.key: Flat\n")
        myFixture.addFileToProject("translations/admin.en.yaml", "title: Admin\n")
        myFixture.addFileToProject("translations/messages+intl-icu.en.yaml", "cart.items: '{count, plural, one {# item} other {# items}}'\n")
        myFixture.addFileToProject(
            "translations/validators.en.xlf",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <xliff xmlns="urn:oasis:names:tc:xliff:document:1.2" version="1.2">
              <file source-language="en" target-language="en" datatype="plaintext" original="file.ext">
                <body>
                  <trans-unit id="a1b2" resname="form.email">
                    <source>form.email</source>
                    <target>Invalid e-mail</target>
                  </trans-unit>
                  <trans-unit id="c3d4">
                    <source>This value is too short.</source>
                  </trans-unit>
                </body>
              </file>
            </xliff>
            """.trimIndent()
        )
    }

    /** The translation Ctrl+click at the caret of [code], written in [fileName], opens. */
    private fun resolvedValue(fileName: String, code: String): String? {
        myFixture.configureByText(fileName, code)
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        return targets.singleOrNull()?.text?.unQuote()?.trim()
    }

    private fun reportsUnresolved(path: String, code: String): Boolean {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.contains(unresolved)
    }

    @Test
    fun theTranslatorResolvesAKeyOfTheMessagesDomain() {
        symfonyProject()
        assertEquals("Name", resolvedValue("a.php", "<?php \$translator->trans('user.na<caret>me');"))
        assertEquals("Name", resolvedValue("b.php", "<?php echo t('user.na<caret>me');"))
        assertTrue(reportsUnresolved("src/Controller/Home.php", "<?php \$this->translator->trans('user.missing');"))
    }

    @Test
    fun theThirdArgumentNamesTheDomain() {
        symfonyProject()
        assertEquals("Admin", resolvedValue("a.php", "<?php \$translator->trans('ti<caret>tle', [], 'admin');"))
        assertEquals("Admin", resolvedValue("b.php", "<?php new TranslatableMessage('ti<caret>tle', [], 'admin');"))
        assertTrue(reportsUnresolved("src/a.php", "<?php \$translator->trans('title', [], 'messages');"), "title is only in admin")
    }

    @Test
    fun aDomainKnownAtRuntimeIsNoKey() {
        symfonyProject()
        assertFalse(reportsUnresolved("src/a.php", "<?php \$translator->trans('anything', [], \$domain);"))
    }

    @Test
    fun aFlatKeyAndAnIcuCatalogueAreRead() {
        symfonyProject()
        assertEquals("Flat", resolvedValue("a.php", "<?php \$translator->trans('flat.k<caret>ey');"))
        assertTrue(resolvedValue("b.php", "<?php \$translator->trans('cart.it<caret>ems');")!!.startsWith("{count, plural"))
    }

    @Test
    fun aGettextCatalogueIsRead() {
        symfonyProject()
        myFixture.addFileToProject("translations/forms.en.po", "msgid \"\"\nmsgstr \"\"\n\nmsgid \"form.submit\"\nmsgstr \"Send\"\n")
        assertEquals("Send", resolvedValue("a.php", "<?php \$translator->trans('form.sub<caret>mit', [], 'forms');"))
        assertTrue(reportsUnresolved("src/a.php", "<?php \$translator->trans('form.missing', [], 'forms');"))
    }

    @Test
    fun anXliffCatalogueIsKeyedByResnameElseBySource() {
        symfonyProject()
        assertEquals("Invalid e-mail", resolvedValue("a.php", "<?php \$translator->trans('form.em<caret>ail', [], 'validators');"))
        assertFalse(reportsUnresolved("src/a.php", "<?php \$translator->trans('This value is too short.', [], 'validators');"))
        assertTrue(reportsUnresolved("src/b.php", "<?php \$translator->trans('form.missing', [], 'validators');"))
    }

    @Test
    fun aKeyWrittenToAnXliffCatalogueIsNamedAsSymfonyReadsIt() {
        symfonyProject()
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("translations/validators.en.xlf"))!!
        WriteCommandAction.runWriteCommandAction(project) { XliffContentGenerator().write(file, "form.name", "Invalid name") }
        val text = file.text
        assertTrue("resname=\"form.name\"" in text, text)
        assertTrue("<source>form.name</source>" in text, text)
        assertTrue("<target>Invalid name</target>" in text, text)
        assertFalse(reportsUnresolved("src/a.php", "<?php \$translator->trans('form.name', [], 'validators');"))
    }

    @Test
    fun twigFiltersAndTagsResolveInTheirDomain() {
        symfonyProject()
        assertEquals("Name", resolvedValue("a.html.twig", "{{ 'user.na<caret>me'|trans }}"))
        assertEquals("Admin", resolvedValue("b.html.twig", "{{ 'ti<caret>tle'|trans({}, 'admin') }}"))
        assertEquals("Admin", resolvedValue("c.html.twig", "{{ 'ti<caret>tle'|trans(domain='admin')|upper }}"))
        assertEquals("Admin", resolvedValue("d.html.twig", "{% trans_default_domain 'admin' %}\n<h1>{{ 'ti<caret>tle'|trans }}</h1>"))
        assertEquals("Name", resolvedValue("e.html.twig", "<p>{% trans %}user.na<caret>me{% endtrans %}</p>"))
        assertEquals("Admin", resolvedValue("f.html.twig", "<p>{% trans from 'admin' %}ti<caret>tle{% endtrans %}</p>"))
    }

    @Test
    fun aTwigKeyIsCompletedInItsDomain() {
        symfonyProject()
        myFixture.addFileToProject("translations/admin.fr.yaml", "title: Administration\nsubtitle: Tableau de bord\n")
        myFixture.configureByText("a.html.twig", "{{ 'ti<caret>'|trans({}, 'admin') }}")
        val offered = myFixture.completeBasic()?.map { it.lookupString }
        assertTrue(offered == null && "'title'" in myFixture.file.text || offered.orEmpty().contains("title"), "offered: $offered")
        myFixture.configureByText("b.html.twig", "{{ 'sub<caret>'|trans }}")
        assertTrue(myFixture.completeBasic().isNullOrEmpty() && "'sub'" in myFixture.file.text, "subtitle is in admin, not in messages")
    }

    @Test
    fun twigKeysAreFoldedIntoTheirTranslation() = myFixture.runWithConfig(Config(foldingEnabled = true, foldingPreferredLanguage = "en")) {
        symfonyProject()
        myFixture.configureByText("a.html.twig", "<h1>{{ 'user.name'|trans }}</h1>\n<p>{{ 'title'|trans({}, 'admin') }}</p>\n<p>{% trans %}user.name{% endtrans %}</p>")
        val text = myFixture.file.text
        val folds = TwigFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
            .map { text.substring(it.range.startOffset, it.range.endOffset) to it.placeholderText }
        assertEquals(
            listOf("'user.name'|trans" to "Name", "'title'|trans({}, 'admin')" to "Admin", "{% trans %}user.name{% endtrans %}" to "Name"),
            folds
        )
    }

    @Test
    fun aMissingTwigKeyIsReported() {
        symfonyProject()
        assertTrue(reportsUnresolved("templates/a.html.twig", "{{ 'user.missing'|trans }}"))
        assertFalse(reportsUnresolved("templates/b.html.twig", "{{ 'user.name'|trans }} {{ 'not a key'|upper }}"))
    }

    @Test
    fun withoutSymfonyTransHoldsNoKey() {
        myFixture.addFileToProject("composer.json", """{"require": {"drupal/core": "^10"}}""")
        assertFalse(reportsUnresolved("src/a.php", "<?php \$translator->trans('user.missing');"))
        assertFalse(reportsUnresolved("templates/a.html.twig", "{{ 'user.missing'|trans }}"))
        assertNull(resolvedValue("b.html.twig", "{{ 'user.na<caret>me'|trans }}"))
    }
}
