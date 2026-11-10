package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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

    /**
     * A Vue component is read by two annotators — the one declared on JavaScript for its script and
     * template expressions, the one declared on Vue for its markup — and by one inspection: each
     * unresolved key, in either part, is still reported once.
     */
    @Test
    fun `an unresolved key of a Vue component is reported once`() = runVue {
        myFixture.addFileToProject("locales/en/test.json", """{"ref": {"title": "x"}}""")
        myFixture.configureByText(
            "Once.vue",
            """
            <template>
              <p>{{ ${'$'}t('test:ref.inTemplate') }}</p>
              <i18n-t keypath="test:ref.inMarkup" tag="p"></i18n-t>
            </template>
            <script setup>
            import { useI18n } from 'vue-i18n'
            const { t } = useI18n()
            const label = t('test:ref.inScript')
            </script>
            """.trimIndent(),
        )
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEquals(listOf("inTemplate", "inMarkup", "inScript"), errors.map { it.text }, errors.map { it.description }.toString())
    }
}
