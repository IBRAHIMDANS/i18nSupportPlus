package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.YamlTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for namespace extraction from the second argument of t(key, options).
 * Covers: { ns: 'name' }, { ns: ['name'] }, { count: 1 } (no ns), { ns: 'name', count: 1 }
 */
class ReferencesOptionsArgTest : PlatformBaseTest() {

    private val jsonTg = JsonTranslationGenerator()
    private val yamlTg = YamlTranslationGenerator()

    // --- ns as string literal ---

    @ParameterizedTest
    @ValueSource(strings = ["js", "ts", "jsx", "tsx"])
    fun testNsStringInOptions(ext: String) {
        myFixture.addFileToProject(
            "assets/common.${jsonTg.ext()}",
            jsonTg.generateContent("ref", "section", "key", "Value from common ns")
        )
        myFixture.configureByText(
            "testNsStringInOptions.$ext",
            "t('ref.section.key<caret>', { ns: 'common' })"
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Wrong resolved value for ext=$ext",
                "Value from common ns",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["js", "ts", "jsx", "tsx"])
    fun testNsStringInOptionsYaml(ext: String) {
        myFixture.addFileToProject(
            "assets/common.${yamlTg.ext()}",
            yamlTg.generateContent("ref", "section", "key", "Value from common ns yaml")
        )
        myFixture.configureByText(
            "testNsStringInOptionsYaml.$ext",
            "t('ref.section.key<caret>', { ns: 'common' })"
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext yaml", element!!.references.isNotEmpty())
            assertEquals(
                "Wrong resolved value for ext=$ext yaml",
                "Value from common ns yaml",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    // --- ns as array ---

    @ParameterizedTest
    @ValueSource(strings = ["js", "ts", "jsx", "tsx"])
    fun testNsArrayInOptions(ext: String) {
        myFixture.addFileToProject(
            "assets/common.${jsonTg.ext()}",
            jsonTg.generateContent("ref", "section", "key", "Value from array ns")
        )
        myFixture.configureByText(
            "testNsArrayInOptions.$ext",
            "t('ref.section.key<caret>', { ns: ['common'] })"
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Wrong resolved value for ext=$ext",
                "Value from array ns",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    // --- options without ns: fallback to default ns ---

    @ParameterizedTest
    @ValueSource(strings = ["js", "ts", "jsx", "tsx"])
    fun testOptionsWithoutNsFallsBackToDefaultNs(ext: String) {
        myFixture.addFileToProject(
            "assets/translation.${jsonTg.ext()}",
            jsonTg.generateContent("ref", "section", "key", "Default ns value")
        )
        myFixture.configureByText(
            "testOptionsWithoutNs.$ext",
            "t('ref.section.key<caret>', { count: 1 })"
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Wrong resolved value for ext=$ext",
                "Default ns value",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    // --- ns combined with other options ---

    @ParameterizedTest
    @ValueSource(strings = ["js", "ts", "jsx", "tsx"])
    fun testNsWithOtherOptions(ext: String) {
        myFixture.addFileToProject(
            "assets/common.${jsonTg.ext()}",
            jsonTg.generateContent("ref", "section", "key", "Value with extra options")
        )
        myFixture.configureByText(
            "testNsWithOtherOptions.$ext",
            "t('ref.section.key<caret>', { ns: 'common', count: 1, context: 'male' })"
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Wrong resolved value for ext=$ext",
                "Value with extra options",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    // JUnit3 compatibility: BasePlatformTestCase requires at least one test* method
    @Test
    fun testPlaceholder() {}
}
