package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * vue-i18n's Composition API, `const { t } = useI18n()`, in a module on the vue-i18n preset: that
 * preset keeps vue-i18n's names, `$t` / `$tc` / `$te`, so its `t('…')` calls were never read.
 */
class VueI18nComposableTest : PlatformBaseTest() {

    private val config = Config(modules = listOf(ModuleConfig(name = "vue", rootDirectory = "src", preset = "vue-i18n")))

    private fun unresolvedIn(path: String, code: String): List<String> {
        myFixture.addFileToProject("src/locales/en.json", """{"order": {"title": "Your order"}, "common": {"save": "Save"}}""")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        val unresolved = PluginBundle.getMessage("annotator.unresolved.key")
        return myFixture.doHighlighting().filter { it.description == unresolved }.map { it.text }
    }

    @Test
    fun theTOfUseI18nIsReadInTheScript() = myFixture.runWithConfig(config) {
        val unresolved = unresolvedIn("src/order.ts", """
            import { useI18n } from 'vue-i18n';
            export function useOrder() {
                const { t, te } = useI18n();
                return [t('order.title'), t('order.missing'), te('common.absent')];
            }
        """.trimIndent())
        assertEquals(listOf("missing", "absent"), unresolved)
    }

    @Test
    fun theTOfUseI18nIsReadInScriptSetup() = myFixture.runWithConfig(config) {
        val unresolved = unresolvedIn("src/components/Order.vue", """
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const title = t('order.title');
            const missing = t('order.inScript');
            </script>
            <template><p>{{ title }}</p></template>
        """.trimIndent())
        assertEquals(listOf("inScript"), unresolved)
    }

    /** The template's `t` is the one `<script setup>` takes from `useI18n()`. */
    @Test
    fun theTOfUseI18nIsReadInTheTemplate() = myFixture.runWithConfig(config) {
        val unresolved = unresolvedIn("src/components/Order.vue", """
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            </script>
            <template><h2>{{ t('order.title') }}</h2><p>{{ t('order.inTemplate') }}</p></template>
        """.trimIndent())
        assertEquals(listOf("inTemplate"), unresolved)
    }

    /** A `t` that `useI18n()` does not return stays out of a vue-i18n module, as before. */
    @Test
    fun aTOfAnotherOriginIsNotRead() = myFixture.runWithConfig(config) {
        assertEquals(emptyList<String>(), unresolvedIn("src/other.ts", "export const label = (t) => t('order.missing');"))
    }
}
