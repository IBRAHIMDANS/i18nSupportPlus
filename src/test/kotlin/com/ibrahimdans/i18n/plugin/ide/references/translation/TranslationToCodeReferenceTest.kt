package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.*
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.ibrahimdans.i18n.plugin.ide.usages.CodeUsageIndexService
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test


private val tgs = listOf(JsonTranslationGenerator())
private val jsCgs = listOf(JsCodeGenerator(), TsCodeGenerator(), JsxCodeGenerator(), TsxCodeGenerator())
private val cgs = jsCgs + listOf(PhpSingleQuoteCodeGenerator(), PhpDoubleQuoteCodeGenerator())

class TranslationToCodeTestBase: PlatformBaseTest() {

    @Test
    fun testSingleReference() {
        cgs.forEachIndexed { i0, cg ->
            val key = "'test:ref.section.key${i0}'"
            myFixture.configureByText("testReference${i0}.${cg.ext()}", cg.generate(key))
            tgs.forEach { tg ->
                myFixture.configureByText(
                    "test.${tg.ext()}",
                    tg.generateContent("ref", "section", "key<caret>${i0}", "Translation at ref section key")
                )
                val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
                assertNotNull(element)
                assertEquals(key.unQuote(), element!!.references[0].resolve()?.text?.unQuote())
            }
        }
    }

    @Test
    fun testInvalidTranslation() {
        tgs.forEach { tg ->
            myFixture.configureByText("invalid.${tg.ext()}", "item<caret> text")
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue(element!!.references.isEmpty())
        }
    }

    /** A key nothing calls carries the reference too: it resolves to nothing, and nothing flags it. */
    @Test
    fun testUnusedKeyResolvesToNothing() {
        tgs.forEach { tg ->
            myFixture.configureByText(
                "test.${tg.ext()}",
                tg.generateContent("ref", "section", "key<caret>", "Translation at ref section key")
            )
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertUnresolvedAndUnflagged(element!!, myFixture)
        }
    }

    /**
     * A key written in more files than the word index calls cheap carried no reference at all: no
     * Ctrl+click, no Find Usages from the translation to its calls.
     */
    @Test
    fun testPopularKeyResolvesToEveryCall() {
        val cg = JsCodeGenerator()
        repeat(POPULAR_FILES) { i -> myFixture.addFileToProject("popular$i.js", cg.generate("'popular:ref.section.key'")) }
        myFixture.configureByText("popular.json", JsonTranslationGenerator().generateContent("ref", "section", "key<caret>", "Popular"))
        @Suppress("DEPRECATION")
        assertEquals(
            PsiSearchHelper.SearchCostResult.TOO_MANY_OCCURRENCES,
            PsiSearchHelper.getInstance(project).isCheapEnoughToSearch("popular:ref.section.key", GlobalSearchScope.projectScope(project), null),
            "the key must be one the old guard left without a reference"
        )
        // Built, as a highlighting pass or a Ctrl+click off the event dispatch thread finds it.
        CodeUsageIndexService.getInstance(project).calls("", emptyList(), null)

        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent!!
        val reference = element.references.filterIsInstance<TranslationToCodeReference>().single()

        val files = reference.multiResolve(false).mapNotNull { it.element?.containingFile?.name }.toSet()
        assertEquals(POPULAR_FILES, files.size, "$files")
    }

    /**
     * A search for the references to a translation key meets this reference in every locale file:
     * another locale's key is no target of it, answered without resolving.
     */
    @Test
    fun testAnotherTranslationIsNoTarget() {
        myFixture.addFileToProject("target.js", JsCodeGenerator().generate("'target:ref.section.key'"))
        val other = myFixture.addFileToProject("fr/target.json", JsonTranslationGenerator().generateContent("ref", "section", "key", "Cible"))
        myFixture.configureByText("target.json", JsonTranslationGenerator().generateContent("ref", "section", "key<caret>", "Target"))

        val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent!!
        val reference = element.references.filterIsInstance<TranslationToCodeReference>().single()
        val otherKey = PsiTreeUtil.findChildrenOfType(other, JsonProperty::class.java).single { it.name == "key" }

        assertFalse(reference.isReferenceTo(otherKey))
        assertFalse(reference.isReferenceTo(otherKey.nameElement))
        val call = reference.multiResolve(false).single().element!!
        assertTrue(reference.isReferenceTo(call), "the call site it resolves to")
    }

    @Test
    fun testMultipleReferences() {
        cgs.forEachIndexed { index, cg ->
            val key = "'multiTest:ref.section.subsection1.key${index}'"
            myFixture.configureByText(
                "multiReference1.${cg.ext()}",
                cg.multiGenerate(
                    key,
                    "skip-multiTest:ref.section.subsection1.key${index}",
                    key
                )
            )
            myFixture.configureByText(
                "multiReference2.${cg.ext()}",
                cg.multiGenerate(
                    key,
                    "skip-multiTest2:ref.section.subsection1.key${index}"
                )
            )
            tgs.forEach { tg ->
                myFixture.configureByText(
                    "multiTest.${tg.ext()}",
                    tg.generateContent("ref", "section", "subsection1", "key<caret>${index}", "Translation")
                )
                val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
                val msg = cg.ext() + "," + tg.ext()
                val ref = element!!.references[0] as? PsiPolyVariantReference
                if (ref == null) {
                    fail(msg)
                } else {
                    assertEquals(msg, setOf(key.unQuote()), ref.multiResolve(true).mapNotNull {it.element?.text?.unQuote()}.toSet())
                }
            }
        }
    }

    @Test
    fun testObjectReference() {
        jsCgs.forEachIndexed { index, cg ->
            tgs.forEach { tg ->
                myFixture.configureByText(
                    "testReference.${cg.ext()}",
                    cg.multiGenerate(
                        "'skip-objectRef:ref.section.key1'",
                        "'objectRef${index}:ref.section.key2'",
                        "`skip-objectRef:ref.section.\${key2}`",
                        "'objectRef${index}:ref.section.key1'",
                        "'skip-objectRef:ref.section.key2'",
                        "`objectRef${index}:ref.section.\${key2}`"
                    )
                )
                myFixture.configureByText(
                    "objectRef${index}.${tg.ext()}",
                    tg.generateContent("ref", "section<caret>", "key1", "val 1")
                )
                val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
                val ref = element!!.references[0]
                assertTrue(ref is PsiPolyVariantReference)
                val refs = (ref as PsiPolyVariantReference).multiResolve(true).mapNotNull { it.element?.text }.toSet()
                assertEquals(
                    cg.ext() + "," + tg.ext(),
                    setOf("'objectRef${index}:ref.section.key1'", "'objectRef${index}:ref.section.key2'", "`objectRef${index}:ref.section.\${key2}`"),
                    refs
                )
            }
        }
    }

    @Test
    fun testInvalidRange() {
        val tg = JsonTranslationGenerator()
        jsCgs.forEachIndexed { index, cg ->
            myFixture.configureByText(
                "testReference.${cg.ext()}",
                cg.multiGenerate(
                    "'skip-objectRef:ref.section.key1'",
                    "'objectRef${index}:ref.section.key2'",
                    "`skip-objectRef:ref.section.\${key2}`",
                    "'objectRef${index}:ref.section.key1'",
                    "'skip-objectRef:ref.section.key2'",
                    "`objectRef${index}:ref.section.\${key2}`"
                )
            )
            myFixture.configureByText(
                "objectRef${index}.${tg.ext()}",
                "{<caret>\""
            )
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue(element!!.references.isEmpty())
        }
    }

    @Test
    fun testDefaultNs() {
        jsCgs.forEachIndexed { index, cg ->
            myFixture.runWithConfig(Config(defaultNs = "Common")) {
                myFixture.configureByText(
                    "testDefaultNs.${cg.ext()}",
                    cg.multiGenerate(
                        "'objectRef:ref.section.key1'",
                        "'ref${index}.section.key2'",
                        "`objectRef:ref.section.\${key3}`",
                        "'ref${index}.section.key1'",
                        "'objectRef:ref.section.key2'",
                        "`ref${index}.section.\${key3}`"
                    )
                )
                tgs.forEach { tg ->
                    myFixture.configureByText(
                        "Common.${tg.ext()}",
                        tg.generateContent("ref${index}", "section<caret>", "key1", "val 1")
                    )
                    val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
                    val ref = element!!.references[0]
                    assertTrue(ref is PsiPolyVariantReference)
                    val refs = (ref as PsiPolyVariantReference).multiResolve(true).mapNotNull { it.element?.text }.toSet()
                    assertEquals(
                        setOf("'ref${index}.section.key1'", "'ref${index}.section.key2'", "`ref${index}.section.\${key3}`"),
                        refs
                    )
                }
            }
        }
    }

    @Test
    fun testClickOnValue() {
        tgs.forEach { tg ->
            myFixture.configureByText(
                "testValue.${tg.ext()}",
                tg.generateContent("ref", "section", "key", "transla<caret>tion")
            )
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue(element!!.references.isEmpty())
        }
    }
}

private const val POPULAR_FILES = 15

/** [element] carries the translation-to-code reference, resolving to nothing, and the file shows no error for it. */
internal fun assertUnresolvedAndUnflagged(element: PsiElement, fixture: CodeInsightTestFixture) {
    val references = element.references.filterIsInstance<TranslationToCodeReference>()
    assertEquals(1, references.size, "${element.references.toList()}")
    assertTrue(references.single().multiResolve(false).isEmpty())
    val flagged = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR || it.description?.contains("resolve") == true }
    assertTrue(flagged.isEmpty(), "$flagged")
}

class YamlReferencesTestBase : PlatformBaseTest() {

    val tg = YamlTranslationGenerator()

    @Test
    fun testSingleReferenceQuoted() {
        cgs.forEachIndexed { index, cg ->
            val key = "'testQuoted:ref.section.key${index}'"
            myFixture.configureByText(
                "testReferenceQuoted.${cg.ext()}",
                cg.generate(key)
            )
            myFixture.configureByText(
                "testQuoted.${tg.ext()}",
                tg.generateContent("ref", "section", "\"key<caret>${index}\"", "Reference in yaml")
            )
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertEquals(key.unQuote(), element!!.references[0].resolve()?.text?.unQuote())
        }
    }
}