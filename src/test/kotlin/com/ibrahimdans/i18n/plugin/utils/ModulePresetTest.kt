package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A module's preset selects the framework whose calls its code files use. Every framework's names
 * used to apply everywhere, so i18next's `t` was claimed in a Vue module too.
 */
class ModulePresetTest : PlatformBaseTest() {

    private val vueModule = ModuleConfig(name = "vue", rootDirectory = "apps/vue", preset = "vue-i18n")

    private val problems = setOf(
        PluginBundle.getMessage("annotator.unresolved.key"),
        PluginBundle.getMessage("annotator.unresolved.ns"),
    )

    private fun annotated(path: String, code: String): Boolean {
        if (myFixture.tempDirFixture.getFile("locales/en/common.json") == null) {
            myFixture.addFileToProject("locales/en/common.json", """{"title": "Hi"}""")
        }
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.any { it in problems }
    }

    @Test
    fun aVuePresetIgnoresI18nextCalls() = myFixture.runWithConfig(Config(modules = listOf(vueModule))) {
        assertFalse(annotated("apps/vue/src/A.js", "t('common:missing')"), "i18next's t is not a call of a vue-i18n module")
        assertTrue(annotated("apps/vue/src/B.js", "\$t('common:missing')"), "vue-i18n's \$t is")
    }

    @Test
    fun outsideThePresetModuleEveryFrameworkApplies() = myFixture.runWithConfig(Config(modules = listOf(vueModule))) {
        assertTrue(annotated("apps/react/src/A.js", "t('common:missing')"))
    }

    @Test
    fun aRuleIncludingACallWinsOverThePreset() = myFixture.runWithConfig(
        Config(modules = listOf(vueModule), rules = listOf(EditorRuleState(language = "js", trigger = "t")))
    ) {
        assertTrue(annotated("apps/vue/src/A.js", "t('common:missing')"))
    }
}
