package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.at
import com.ibrahimdans.i18n.plugin.utils.generator.code.PhpGetTextCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.PoTranslationGenerator
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled

// PO→Code navigation requires implementing PlainObjectReferenceAssistant.pattern() and references()
// using PSI types from org.jetbrains.plugins.localization. The plugin is now available (build 251+)
// but the assistant still returns alwaysFalse()/emptyList(). Re-enable once implemented.
@Disabled
class PoToPhpReferenceTest: PlatformBaseTest() {

    private val cg = PhpGetTextCodeGenerator("gettext")
    private val tg = PoTranslationGenerator()

    fun testDisabledTranslationToCodeReference() {
        val translation = tg.generateContent("ref", "section<caret>", "key1", "val 1")
        myFixture.configureByText(
            "test.php",
            cg.multiGenerate(
                "'skip.ref.section.key1'",
                "'ref.section.key1'",
                "'drop.ref.section.key3'",
                "'skpref.section.key1'",
                "'ref.section.key1'"
            )
        )
//        myFixture.configureByText("file.po", translation)
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject("en-US/LC_MESSAGES/messages.${tg.ext()}", translation).virtualFile
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
        val ref = element?.references?.at(0)
        assertTrue(ref is PsiPolyVariantReference)
        assertEquals(
            setOf("'ref.section.key1'"),
            (ref as PsiPolyVariantReference).multiResolve(true).mapNotNull { it.element?.text }.toSet()
        )
    }


}