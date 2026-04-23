package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.ibrahimdans.i18n.plugin.utils.unQuote
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for namespace extraction from useTranslation() hook.
 * Covers: useTranslation('ns'), useTranslation(['ns1', 'ns2'])
 */
class ReferencesUseTranslationArrayTest : PlatformBaseTest() {

    private val tg = JsonTranslationGenerator()

    private fun useTranslationCode(hookArg: String, key: String, ext: String): String = """
        import { useTranslation } from 'react-i18next';
        export default function Component() {
            const { t } = useTranslation($hookArg);
            return t('$key<caret>');
        }
    """.trimIndent()

    @ParameterizedTest
    @ValueSource(strings = ["jsx", "tsx"])
    fun testSingleStringNamespace(ext: String) {
        addFileToProject(
            "assets/dashboard.${tg.ext()}",
            tg.generateContent("title", "Dashboard")
        )
        myFixture.configureByText(
            "testSingleStringNs.$ext",
            useTranslationCode("'dashboard'", "title", ext)
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Dashboard",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jsx", "tsx"])
    fun testArrayNamespaceSingleEntry(ext: String) {
        addFileToProject(
            "assets/dashboard.${tg.ext()}",
            tg.generateContent("main", "title", "Dashboard Title")
        )
        myFixture.configureByText(
            "testArrayNsSingle.$ext",
            useTranslationCode("['dashboard']", "main.title", ext)
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Dashboard Title",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jsx", "tsx"])
    fun testArrayNamespaceBothResolve(ext: String) {
        addFileToProject(
            "assets/dashboard.${tg.ext()}",
            tg.generateContent("main", "title", "Dashboard Title")
        )
        addFileToProject(
            "assets/common.${tg.ext()}",
            tg.generateContent("actions", "save", "Save")
        )
        myFixture.configureByText(
            "testArrayNsBoth.$ext",
            useTranslationCode("['dashboard', 'common']", "main.title", ext)
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            val resolved = element.references.mapNotNull { it.resolve()?.text?.unQuote() }.toSet()
            assertTrue(
                "Expected 'Dashboard Title' in resolved: $resolved",
                resolved.contains("Dashboard Title")
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jsx", "tsx"])
    fun testArrayNamespaceSecondary(ext: String) {
        addFileToProject(
            "assets/dashboard.${tg.ext()}",
            tg.generateContent("title", "Dashboard Title")
        )
        addFileToProject(
            "assets/common.${tg.ext()}",
            tg.generateContent("actions", "save", "Save")
        )
        myFixture.configureByText(
            "testArrayNsSecondary.$ext",
            useTranslationCode("['dashboard', 'common']", "actions.save", ext)
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Save",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jsx", "tsx"])
    fun testArrayNamespaceWithExplicitPrefix(ext: String) {
        addFileToProject(
            "assets/dashboard.${tg.ext()}",
            tg.generateContent("title", "Dashboard Title")
        )
        addFileToProject(
            "assets/common.${tg.ext()}",
            tg.generateContent("actions", "save", "Save")
        )
        myFixture.configureByText(
            "testArrayNsExplicit.$ext",
            useTranslationCode("['dashboard', 'common']", "common:actions.save", ext)
        )
        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)?.parent
            assertNotNull(element)
            assertTrue("No references found for ext=$ext", element!!.references.isNotEmpty())
            assertEquals(
                "Save",
                element.references[0].resolve()?.text?.unQuote()
            )
        }
    }

    // JUnit3 compatibility: BasePlatformTestCase requires at least one test* method
    @Test
    fun testPlaceholder() {}
}
