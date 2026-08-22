package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.extensions.localization.plain.`object`.PlainObjectReferenceAssistant
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PoToPhpReferenceTest: PlatformBaseTest() {

    private val cg = PhpGetTextCodeGenerator("gettext")
    private val tg = PoTranslationGenerator()
    private val config = Config(gettext = true)

    /**
     * The msgid resolves onto the calls that use it, and onto those alone: a key the msgid is only a
     * substring of (`skip.ref.section.key1`, `skpref.section.key1`) is not a usage of it.
     */
    @Test
    fun msgidResolvesOntoItsCallsOnly() = myFixture.runWithConfig(config) {
        val element = configureFixture()
        val ref = PlainObjectReferenceAssistant().references(element).firstOrNull()
        assertTrue(ref is PsiPolyVariantReference, "a msgid must carry a poly variant reference")
        val resolved = (ref as PsiPolyVariantReference).multiResolve(true).mapNotNull { it.element }
        assertEquals(setOf("ref.section.key1"), resolved.map { it.text }.toSet())
        assertEquals(2, resolved.size, "both calls of the msgid must be reached, not just the first")
    }

    /**
     * Pins what the user actually does — Ctrl+click on the msgid — which no reference contributor can
     * serve here: the PO PSI holds no `ContributedReferenceHost`, so navigation goes through
     * `PlainObjectGotoDeclarationHandler`. Failing here means the handler is unregistered.
     */
    @Test
    fun ctrlClickOnMsgidReachesTheCode() = myFixture.runWithConfig(config) {
        configureFixture()
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        assertEquals(setOf("ref.section.key1"), targets.map { it.text }.toSet())
    }

    /** The PO header carries no key and must not offer navigation. */
    @Test
    fun headerMsgidIsNotAKey() = myFixture.runWithConfig(config) {
        myFixture.configureByText("header.php", cg.multiGenerate("'ref.section.key1'"))
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject(
                "en-US/LC_MESSAGES/header.${tg.ext()}",
                "msgid \"<caret>\"\nmsgstr \"Content-Type: text/plain\"\n"
            ).virtualFile
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element)
        assertTrue(PlainObjectReferenceAssistant().references(element!!).isEmpty())
    }

    private fun configureFixture(): PsiElement {
        myFixture.configureByText(
            "test.${cg.ext()}",
            cg.multiGenerate(
                "'skip.ref.section.key1'",
                "'ref.section.key1'",
                "'drop.ref.section.key3'",
                "'skpref.section.key1'",
                "'ref.section.key1'"
            )
        )
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject(
                "en-US/LC_MESSAGES/messages.${tg.ext()}",
                tg.generateContent("ref", "section<caret>", "key1", "val 1")
            ).virtualFile
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        assertNotNull(element, "caret must land inside the msgid literal")
        return element!!
    }
}
