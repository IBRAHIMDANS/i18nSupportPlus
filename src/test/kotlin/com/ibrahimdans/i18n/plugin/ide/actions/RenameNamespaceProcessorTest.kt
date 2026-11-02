package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Shift+F6 on a namespace file or folder renames the namespace: the file in every locale, the
 * `common:` prefix of its keys, `useTranslation('common')`, the `ns` option, and the default
 * namespace of the settings. Off a namespace — a file the plugin does not read — or with the option
 * unticked, the rename is the IDE's own.
 */
class RenameNamespaceProcessorTest : PlatformBaseTest() {

    private fun text(path: String): String? = ReadAction.compute<String?, RuntimeException> {
        myFixture.tempDirFixture.getFile(path)?.let { PsiManager.getInstance(project).findFile(it)?.text }
    }

    private fun exists(path: String): Boolean = myFixture.tempDirFixture.getFile(path) != null

    private fun psiFile(path: String) = ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!
    }

    private fun psiFolder(path: String) = ReadAction.compute<com.intellij.psi.PsiDirectory, RuntimeException> {
        PsiManager.getInstance(project).findDirectory(myFixture.findFileInTempDir(path))!!
    }

    private fun addLocales() {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        addFileToProject("locales/en/other.json", """{"save": "Other"}""")
    }

    private fun addCallSites() {
        addFileToProject("src/Prefix.js", "export const a = (t) => [t('common:save'), t('other:save'), t('commonly:save')];")
        addFileToProject(
            "src/Hook.tsx",
            "import { useTranslation, Trans } from 'react-i18next';\n" +
                "export const H = () => { const { t } = useTranslation('common'); const { t: t2 } = useTranslation(['common', 'other']);\n" +
                "  return <p>{t('save')}{t2('save', { ns: 'common' })}<Trans ns=\"common\" i18nKey=\"save\"/><Trans ns={['common', 'other']} i18nKey=\"save\"/></p>; };\n" +
                "export const label = 'common';"
        )
    }

    @Test
    fun theNamespaceIsRenamedInEveryLocaleAndCallSite() = myFixture.runWithConfig(Config(defaultNs = "common", fallbackNs = "other")) {
        addLocales()
        addCallSites()

        // From the French file: the call sites are not tied to the locale the rename starts from.
        myFixture.renameElement(psiFile("locales/fr/common.json"), "shared.json")

        Assertions.assertTrue(exists("locales/en/shared.json") && exists("locales/fr/shared.json"), "every locale's file is renamed")
        Assertions.assertFalse(exists("locales/en/common.json") || exists("locales/fr/common.json"), "no common.json is left behind")
        Assertions.assertTrue(exists("locales/en/other.json"), "another namespace is left alone")

        Assertions.assertEquals(
            "export const a = (t) => [t('shared:save'), t('other:save'), t('commonly:save')];",
            text("src/Prefix.js")
        )
        val hook = text("src/Hook.tsx")!!
        Assertions.assertTrue(hook.contains("useTranslation('shared')"), hook)
        Assertions.assertTrue(hook.contains("useTranslation(['shared', 'other'])"), hook)
        Assertions.assertTrue(hook.contains("{ ns: 'shared' }"), hook)
        Assertions.assertTrue(hook.contains("<Trans ns=\"shared\""), hook)
        Assertions.assertTrue(hook.contains("<Trans ns={['shared', 'other']}"), hook)
        Assertions.assertTrue(hook.contains("export const label = 'common';"), "a plain string is no namespace: $hook")

        val config = Settings.getInstance(project).config()
        Assertions.assertEquals("shared", config.defaultNs, "the default namespace of the settings follows")
        Assertions.assertEquals("other", config.fallbackNs, "a fallback naming another namespace is kept")
    }

    @Test
    fun aFolderPerNamespaceIsRenamedWithItsCallSites() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "app", pathTemplate = "locales/{ns}/{lang}.json")))
    ) {
        addFileToProject("locales/common/en.json", """{"save": "Save"}""")
        addFileToProject("locales/common/fr.json", """{"save": "Enregistrer"}""")
        addFileToProject("locales/other/en.json", """{"save": "Other"}""")
        addFileToProject("src/Prefix.js", "export const a = (t) => [t('common:save'), t('other:save')];")

        ReadAction.run<RuntimeException> {
            Assertions.assertTrue(RenameNamespaceProcessor().canProcessElement(psiFolder("locales/common")), "the folder holds the namespace")
            Assertions.assertInstanceOf(
                RenameNamespaceProcessor::class.java,
                com.intellij.refactoring.rename.RenamePsiElementProcessor.forElement(psiFolder("locales/common")),
                "the namespace's rename is the plugin's"
            )
        }
        myFixture.renameElement(psiFolder("locales/common"), "shared")

        Assertions.assertTrue(exists("locales/shared/en.json") && exists("locales/shared/fr.json"), "the folder is renamed")
        Assertions.assertFalse(exists("locales/common"), "no common folder is left behind")
        Assertions.assertEquals("export const a = (t) => [t('shared:save'), t('other:save')];", text("src/Prefix.js"))
    }

    @Test
    fun aFileTheTranslationsDoNotHoldKeepsTheRenameOfTheIde() {
        addLocales()
        addFileToProject("data/common.json", """{"save": 1}""")
        addFileToProject("locales/de.json", """{"save": "Speichern"}""")
        addFileToProject("src/Prefix.js", "export const a = (t) => t('common:save');")

        val processor = RenameNamespaceProcessor()
        ReadAction.run<RuntimeException> {
            Assertions.assertFalse(processor.canProcessElement(psiFile("data/common.json")), "no translation source holds it")
            Assertions.assertFalse(processor.canProcessElement(psiFile("locales/de.json")), "a locale-named file holds no namespace")
            Assertions.assertTrue(processor.canProcessElement(psiFile("locales/en/common.json")))
        }

        myFixture.renameElement(psiFile("data/common.json"), "shared.json")

        Assertions.assertTrue(exists("data/shared.json"))
        Assertions.assertTrue(exists("locales/en/common.json") && exists("locales/fr/common.json"), "the namespace is not touched")
        Assertions.assertEquals("export const a = (t) => t('common:save');", text("src/Prefix.js"))
    }

    /** Laravel writes the namespace with the key separator (`{ns}.{key}`): `messages.welcome` names the file `messages.php`. */
    @Test
    fun aLaravelFileIsRenamedWithItsDottedCallSites() {
        val messages = "<?php\n\nreturn [\n    'welcome' => 'Welcome',\n    'auth' => ['failed' => 'Failed'],\n];\n"
        addFileToProject("composer.json", """{"require": {"php": "^8.2", "laravel/framework": "^11.0"}}""")
        addFileToProject("lang/en/messages.php", messages)
        addFileToProject("lang/fr/messages.php", messages)
        addFileToProject("lang/en/auth.php", "<?php\n\nreturn ['messages' => 'Other'];\n")
        addFileToProject(
            "app/Http/Controllers/HomeController.php",
            "<?php\n\nclass HomeController {\n    public function index() {\n" +
                "        return [__('messages.welcome'), trans('messages.auth.failed'), __('auth.messages'), __('messagesx.welcome')];\n    }\n}\n"
        )

        myFixture.renameElement(psiFile("lang/en/messages.php"), "notices.php")

        Assertions.assertTrue(exists("lang/en/notices.php") && exists("lang/fr/notices.php"), "every locale's file is renamed")
        val controller = text("app/Http/Controllers/HomeController.php")!!
        Assertions.assertTrue(controller.contains("__('notices.welcome')"), controller)
        Assertions.assertTrue(controller.contains("trans('notices.auth.failed')"), controller)
        Assertions.assertTrue(controller.contains("__('auth.messages')"), "a key segment of another file is no namespace: $controller")
        Assertions.assertTrue(controller.contains("__('messagesx.welcome')"), controller)
    }

    @Test
    fun theOptionUntickedRenamesTheFileAlone() {
        addLocales()
        addCallSites()
        val option = RenameNamespaceOption()
        option.setEnabled(false)
        try {
            myFixture.renameElement(psiFile("locales/en/common.json"), "shared.json")
        } finally {
            option.setEnabled(true)
        }

        Assertions.assertTrue(exists("locales/en/shared.json"))
        Assertions.assertTrue(exists("locales/fr/common.json"), "the other locales keep their file")
        Assertions.assertEquals(
            "export const a = (t) => [t('common:save'), t('other:save'), t('commonly:save')];",
            text("src/Prefix.js")
        )
    }
}
