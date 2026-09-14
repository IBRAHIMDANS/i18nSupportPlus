package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * One problem, one annotation. A key was annotated twice in every JS dialect — its literal
 * expression and its leaf token were both claimed — and three times in a `.tsx`, which also ran
 * the JSX annotator next to the JavaScript one it inherits.
 */
class AnnotationCountTest : PlatformBaseTest() {

    @ParameterizedTest
    @ValueSource(strings = ["js", "jsx", "ts", "tsx"])
    fun `an unresolved key is annotated once`(ext: String) {
        myFixture.addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")
        myFixture.configureByText("Once.$ext", "export const a = (t) => t('test:ref.missing');")
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("Unresolved key"), errors.map { it.description }, ".$ext")
    }
}
