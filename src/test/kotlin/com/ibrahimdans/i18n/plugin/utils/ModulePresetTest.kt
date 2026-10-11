package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.LayoutPresets
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

    /** react-intl's preset, the one #360 first suggested for next-intl, would drop this `t`. */
    @Test
    fun aNextIntlPresetKeepsTheTOfUseTranslations() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web", preset = "next-intl")))
    ) {
        assertTrue(
            annotated(
                "apps/web/src/Home.tsx",
                "import { useTranslations } from 'next-intl';\n" +
                    "export const Home = () => { const t = useTranslations('Home'); return t('missing'); };"
            ),
            "next-intl's t is a call of a next-intl module"
        )
        assertFalse(annotated("apps/web/src/B.js", "\$t('common:missing')"), "vue-i18n's \$t is not")
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

    /** The Laravel preset's layout: the file names the namespace, `.` separates it from the key. */
    private val laravelModule = LayoutPresets.apply(
        ModuleConfig(name = "backend", rootDirectory = "backend", preset = "laravel"),
        LayoutPresets.of("laravel")!!
    )

    private fun laravelProblems(path: String, code: String): List<String> {
        if (myFixture.tempDirFixture.getFile("backend/composer.json") == null) {
            myFixture.addFileToProject("backend/composer.json", """{"require": {"laravel/framework": "^11.0"}}""")
            myFixture.addFileToProject("backend/lang/en/messages.php", "<?php\n\nreturn [\n    'welcome' => 'Welcome',\n];\n")
        }
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code).virtualFile)
        return myFixture.doHighlighting().filter { it.description in problems }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun aLaravelPresetReadsTheFileAsTheNamespace() = myFixture.runWithConfig(Config(modules = listOf(laravelModule))) {
        assertTrue(laravelProblems("backend/app/Home.php", "<?php echo __('messages.welcome');").isEmpty(), "messages.welcome is in lang/en/messages.php")
        assertTrue(laravelProblems("backend/app/About.php", "<?php echo __('messages.zzMissing');").isNotEmpty(), "a key missing from the file is reported")
    }

    /** `lang/en.json` holds whole sentences as keys: the `{ns}.{key}` template must not split them. */
    @Test
    fun aLaravelPresetKeepsTheSentencesOfTheJsonFile() = myFixture.runWithConfig(Config(modules = listOf(laravelModule))) {
        myFixture.addFileToProject("backend/lang/en.json", """{"Welcome to our app": "Welcome!", "Done.": "Done!"}""")
        assertTrue(laravelProblems("backend/app/Welcome.php", "<?php echo __('Welcome to our app');").isEmpty())
        assertTrue(laravelProblems("backend/app/Done.php", "<?php echo __('Done.');").isEmpty())
        assertTrue(laravelProblems("backend/app/Nope.php", "<?php echo __('zzNot a sentence of the file');").isNotEmpty())
    }
}
