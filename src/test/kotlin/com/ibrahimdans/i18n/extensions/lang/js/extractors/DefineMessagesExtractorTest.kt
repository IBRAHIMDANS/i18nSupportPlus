package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.lang.js.JsxLang
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-level checks on the react-intl catalogue extractor, independent of the annotator
 * pipeline.
 */
class DefineMessagesExtractorTest : PlatformBaseTest() {

    private val extractor = DefineMessagesExtractor()
    private val fnNames = listOf("t", "i18n.t", "formatMessage")

    private fun literals(code: String): List<JSLiteralExpression> {
        myFixture.configureByText("test.tsx", code)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java).toList()
    }

    /** Maps every string literal in [code] to whether the extractor claims it. */
    private fun extractionMap(code: String): Map<String, Boolean> =
        literals(code).associate { it.text to extractor.canExtract(it) }

    private fun extractedValues(code: String): List<String> =
        literals(code).filter { extractor.canExtract(it) }
            .map { extractor.extract(it).keyElements.joinToString("") { e -> e.text } }

    @Test
    fun testEveryDescriptorIdOfACatalogueIsExtracted() {
        assertEquals(
            listOf("app.greeting", "app.farewell"),
            extractedValues(
                """
                const messages = defineMessages({
                  greeting: { id: "app.greeting", defaultMessage: "Hello" },
                  farewell: { id: "app.farewell", defaultMessage: "Bye" },
                });
                """.trimIndent()
            )
        )
    }

    @Test
    fun testSingularDefineMessageIsExtracted() {
        assertEquals(
            mapOf("\"app.greeting\"" to true, "\"Hello\"" to false),
            extractionMap("const m = defineMessage({ id: \"app.greeting\", defaultMessage: \"Hello\" });")
        )
    }

    @Test
    fun testDefaultMessageAndDescriptionAreNeverExtracted() {
        assertEquals(
            mapOf(
                "\"app.greeting\"" to true,
                "\"Hello\"" to false,
                "\"Shown on the home page\"" to false,
            ),
            extractionMap(
                """
                const messages = defineMessages({
                  greeting: {
                    id: "app.greeting",
                    defaultMessage: "Hello",
                    description: "Shown on the home page",
                  },
                });
                """.trimIndent()
            )
        )
    }

    @Test
    fun testPlainObjectIsIgnored() {
        assertEquals(
            mapOf("\"app.greeting\"" to false, "\"Hello\"" to false),
            extractionMap("const messages = { greeting: { id: \"app.greeting\", defaultMessage: \"Hello\" } };")
        )
    }

    @Test
    fun testUnrelatedCallIsIgnored() {
        assertEquals(
            mapOf("\"app.greeting\"" to false),
            extractionMap("const m = register({ greeting: { id: \"app.greeting\" } });")
        )
    }

    @Test
    fun testFormatMessageDescriptorStaysWithReactIntlExtractor() {
        val code = "const f = (intl) => intl.formatMessage({ id: \"app.greeting\" });"
        assertEquals(mapOf("\"app.greeting\"" to false), extractionMap(code))
        assertEquals(
            mapOf("\"app.greeting\"" to true),
            literals(code).associate { it.text to ReactIntlExtractor().canExtract(it) }
        )
    }

    @Test
    fun testCatalogueIdTravelsTheLangPipeline() {
        val literal = literals(
            """
            const messages = defineMessages({
              greeting: { id: "app.greeting", defaultMessage: "Hello" },
            });
            """.trimIndent()
        ).first { it.text == "\"app.greeting\"" }

        val lang = JsxLang()
        assertTrue(lang.canExtractKey(literal, fnNames), "canExtractKey")
        val rawKey = lang.extractRawKey(literal)
        assertNotNull(rawKey, "extractRawKey")
        val fullKey = RawKeyParser(myFixture.project).parse(rawKey!!)
        assertNotNull(fullKey, "RawKeyParser")
        assertEquals("app.greeting", fullKey!!.source)
    }

    @Test
    fun testLangVetoesDefaultMessageOfACatalogue() {
        val literal = literals(
            """
            const messages = defineMessages({
              greeting: { id: "app.greeting", defaultMessage: "Hello" },
            });
            """.trimIndent()
        ).first { it.text == "\"Hello\"" }

        assertFalse(JsxLang().canExtractKey(literal, fnNames), "canExtractKey on defaultMessage")
    }
}
