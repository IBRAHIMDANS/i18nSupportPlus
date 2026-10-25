package com.ibrahimdans.i18n.extract.vuei18n

import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.parser.ExpressionNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class VueI18nKeyExtractorsTest {

    /** `line: key` for each key of the component [text], read as `i18n-check` reads a `.vue` file. */
    private fun keysOf(text: String, preset: String? = CodeExtractors.VUE_I18N): List<String> {
        val code = VueSfc.codeOf(text)
        assertEquals(text.length, code.length, "the code keeps the component's offsets")
        val normalizer = ExpressionNormalizer()
        return findKeys(TreeSitterCodeNode.parseTsx(code), CodeExtractors.forPreset(preset)).map { (node, key) ->
            val line = text.substring(0, node.span.start).count { it == '\n' } + 1
            "$line: " + key.keyElements.mapNotNull(normalizer::normalize).joinToString("") { it.text }
        }
    }

    @Test
    fun `the template's interpolations, bound attributes and markup hold keys`() {
        val component = """
            <template>
              <h1>{{ ${'$'}t('a') }}</h1>
              <p :title="${'$'}t('b')" @click="go(${'$'}t('c'))">text t('not.a.key')</p>
              <p v-t="'d'"></p>
              <p v-t="{ path: 'e', args: { name: 'not.a.key' } }"></p>
              <i18n-t keypath="f" tag="p"><template #link>{{ ${'$'}t('g') }}</template></i18n-t>
              <i18n-t :keypath="'h'"></i18n-t>
              <!-- {{ ${'$'}t('commented') }} -->
              <button v-if="${'$'}te('i')">{{ ${'$'}tc('j', 2) }}</button>
            </template>
        """.trimIndent()

        assertEquals(listOf("2: a", "3: b", "3: c", "4: d", "5: e", "6: f", "6: g", "7: h", "9: i", "9: j"), keysOf(component))
    }

    @Test
    fun `the scripts are read, the i18n block and the style are not`() {
        val component = """
            <i18n>{ "en": { "t('x')": "not code" } }</i18n>
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const props = defineProps<{ status: string }>();
            const title = t('order.title');
            </script>
            <template><p>{{ t('in.template') }}</p></template>
            <style>.a::before { content: "${'$'}t('x')"; }</style>
        """.trimIndent()

        assertEquals(listOf("6: order.title", "8: in.template"), keysOf(component))
    }

    @Test
    fun `under the vue-i18n preset, a t not taken from useI18n is no key`() {
        val component = "<script setup>\nconst { t } = useTranslation();\nt('a');\n</script>\n<template>{{ ${'$'}t('b') }}</template>"

        assertEquals(listOf("5: b"), keysOf(component))
        assertEquals(listOf("3: a", "5: b"), keysOf(component, preset = null))
    }

    @Test
    fun `the example's components`() {
        val example = File("..").canonicalFile.resolve("examples/vue-i18n/src")
        assertEquals(
            listOf(
                "16: home.title", "17: home.greeting", "18: cart.items", "19: actions.saveHint", "20: home.onlyInEnglish",
                "21: home.doesNotExist", "24: common.cancel", "25: home.greeting", "27: terms.accept", "29: terms.link",
                "33: common.switchLocale", "33: common.switchLocale",
            ),
            keysOf(example.resolve("App.vue").readText()),
        )
        assertEquals(
            listOf("9: order.title", "11: order.status.\${props.status}", "18: common.save"),
            keysOf(example.resolve("components/OrderSummary.vue").readText()),
        )
    }
}
