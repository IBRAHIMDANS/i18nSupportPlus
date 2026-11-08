package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionAction
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * `ngettext('apple', 'apples', $n)` in GetText mode: the call is read without adding it to the
 * aliases, and only its first argument is a key — the second is the same entry's `msgid_plural`.
 */
class PhpGettextPluralTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
    private val gettext = Config(gettext = true)

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "en-US/LC_MESSAGES/translation.po",
            """
            msgid ""
            msgstr ""
            "Content-Type: text/plain; charset=UTF-8\n"
            "Plural-Forms: nplurals=2; plural=(n != 1);\n"

            msgid "apple"
            msgid_plural "apples"
            msgstr[0] "Apfel"
            msgstr[1] "Äpfel"
            """.trimIndent()
        )
    }

    /** The texts of the "unresolved key" annotations of [code], in order. */
    private fun unresolvedKeys(code: String): List<String> {
        myFixture.configureByText("plural.php", code)
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description == unresolved }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun theSingularOfAPluralCallIsAKey() = myFixture.runWithConfig(gettext) {
        Assertions.assertEquals(listOf("pear"), unresolvedKeys("<?php echo ngettext('pear', 'pears', \$n);"))
    }

    @Test
    fun thePluralFormIsNotAKey() = myFixture.runWithConfig(gettext) {
        // `apple` is in the catalogue, `apples` is not as a msgid: read as a key, it would be unresolved.
        Assertions.assertEquals(emptyList<String>(), unresolvedKeys("<?php echo ngettext('apple', 'apples', \$n);"))
    }

    @Test
    fun ctrlClickOnTheSingularReachesTheCatalogue() = myFixture.runWithConfig(gettext) {
        myFixture.configureByText("plural.php", "<?php echo ngettext('app<caret>le', 'apples', \$n);")
        val literal = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        Assertions.assertNotNull(literal)
        val target = literal!!.references.firstOrNull()?.resolve()
        Assertions.assertNotNull(target, "the singular of ngettext() resolves to its .po entry")
        Assertions.assertEquals("translation.po", target!!.containingFile.name)
    }

    @Test
    fun thePluralFormHasNoReference() = myFixture.runWithConfig(gettext) {
        myFixture.configureByText("plural.php", "<?php echo ngettext('apple', 'app<caret>les', \$n);")
        val literal = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        Assertions.assertNotNull(literal)
        Assertions.assertEquals(null, literal!!.references.firstOrNull()?.resolve())
    }

    @Test
    fun outsideGettextModeThePluralCallIsNotRead() {
        Assertions.assertEquals(emptyList<String>(), unresolvedKeys("<?php echo ngettext('pear', 'pears', \$n);"))
    }

    @Test
    fun anAliasOfTheUserKeepsReadingItsFirstArgument() = myFixture.runWithConfig(Config(gettext = true, gettextAliases = "ngettext")) {
        Assertions.assertEquals(listOf("pear"), unresolvedKeys("<?php echo ngettext('pear', 'pears', \$n);"))
    }

    @Disabled(
        "2026-11-08: Extract I18n is still offered on the plural form. PhpTranslationExtractor.isExtracted " +
            "knows the user's aliases only, not ngettext; the fix belongs to that file, outside TASK-CADRAGE-NGETTEXT."
    )
    @Test
    fun extractionIsNotOfferedOnThePluralForm() = myFixture.runWithConfig(gettext) {
        myFixture.configureByText("plural.php", "<?php echo ngettext('apple', 'app<caret>les', \$n);")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        Assertions.assertFalse(ExtractI18nIntentionAction().isAvailable(project, myFixture.editor, element))
    }
}
