package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.HookGives
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The hook names the presets declare reach the editor: an alias added to a preset is seen by the
 * extraction's `t` lookup and by the `keyPrefix` rename, not by one of them only.
 */
class PresetHookNamesTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun literal(file: PsiFile, value: String): JSLiteralExpression =
        ReadAction.compute<JSLiteralExpression, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java).first { it.stringValue == value }
        }

    private fun hookAround(file: PsiFile): TranslationHookInScope.Hook? =
        ReadAction.compute<TranslationHookInScope.Hook?, RuntimeException> { TranslationHookInScope.find(literal(file, "Hello")) }

    private fun component(hook: String) = """
        function component() {
            const { t } = $hook('account');
            return 'Hello';
        }
    """.trimIndent()

    @Test
    fun everyHookGivingNamespacesBindsTheTAnExtractionCalls() = myFixture.runWithConfig(config) {
        FrameworkPresets.hookNames(HookGives.NAMESPACES).forEach { hook ->
            val file = myFixture.addFileToProject("src/namespaces/$hook.ts", component(hook))
            Assertions.assertEquals(TranslationHookInScope.Hook(listOf("account")), hookAround(file), hook)
        }
    }

    @Test
    fun aHookGivingAKeyPrefixBindsNoWholeKeyT() = myFixture.runWithConfig(config) {
        FrameworkPresets.hookNames(HookGives.KEY_PREFIX).forEach { hook ->
            val file = myFixture.addFileToProject("src/prefix/$hook.ts", component(hook))
            Assertions.assertNull(hookAround(file), hook)
        }
    }

    @Test
    fun theKeyPrefixOfEveryHookHavingOneIsRenamed() = myFixture.runWithConfig(config) {
        val withPrefix = FrameworkPresets.ALL.flatMap { it.hooks }.filter { it.keyPrefixOption != null }
        Assertions.assertFalse(withPrefix.isEmpty())
        withPrefix.forEach { rule ->
            val file = myFixture.addFileToProject(
                "src/keyPrefix/${rule.name}.ts",
                "const { t } = ${rule.name}('common', { ${rule.keyPrefixOption}: 'button.primary' });",
            )
            val rewrite = ReadAction.compute<String?, RuntimeException> {
                JsKeyRenameSites.prefixRewrite(literal(file, "button.primary"), listOf("button"), "action", listOf("common"), config)?.text
            }
            Assertions.assertEquals("'action.primary'", rewrite, rule.name)
        }
    }
}
