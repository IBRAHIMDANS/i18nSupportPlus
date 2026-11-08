package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.JsLang
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
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

    /**
     * A template literal is read as the key parser reads one elsewhere, static parts and interpolations: the
     * preset leaves `t` out of the generic path, so only this extractor reads it, and it read an empty key.
     */
    @Test
    fun aTemplateLiteralKeyOfUseI18nKeepsItsStaticPrefix() = myFixture.runWithConfig(config) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/status.ts", """
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            export const label = (status: string) => t(`order.status.${'$'}{status}`);
        """.trimIndent()).virtualFile)
        val key = ReadAction.compute<String?, RuntimeException> {
            val template = PsiTreeUtil.findChildOfType(myFixture.file, JSStringTemplateExpression::class.java)!!
            val names = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
            val lang = JsLang()
            if (lang.canExtractKey(template, names)) lang.extractRawKey(template)?.keyElements?.joinToString("") { it.text } else null
        }
        // The elements as `TemplateKeyExtractor` reads a template: its backticks, which the key parser drops, included.
        assertEquals("`order.status.${'$'}{status}`", key)
    }

    /** A `t` that `useI18n()` does not return stays out of a vue-i18n module, as before. */
    @Test
    fun aTOfAnotherOriginIsNotRead() = myFixture.runWithConfig(config) {
        assertEquals(emptyList<String>(), unresolvedIn("src/other.ts", "export const label = (t) => t('order.missing');"))
    }
}
