package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A key created from the translation dialog in a project storing flat keys is written as typed.
 *
 * The flat parser never splits a key, so the namespace the combo prepended was written as part of
 * the key: creating `OverviewActions.help.caption` from `locales/en.json` wrote
 * `"translation:OverviewActions.help.caption"`.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class TranslationDialogFlatKeysTest : PlatformBaseTest() {

    private fun create(config: Config, key: String, locale: String, value: String) {
        val dialog = TranslationDialog(project, KeysSynchronizer().buildFullKey(key, config), Mode.CREATE)
        try {
            dialog.setKey(key)
            dialog.setValue(locale, value)
            dialog.confirm()
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    @Test
    fun `a flat key is written without the default namespace`() = myFixture.runWithConfig(Config(flatKeys = true)) {
        val en = addFileToProject("locales/en.json", """{"OverviewActions.help.title": "Help center"}""").virtualFile
        addFileToProject("locales/fr.json", "{}")

        create(Config(flatKeys = true), "OverviewActions.help.caption", "en", "Help")

        Assertions.assertTrue("\"OverviewActions.help.caption\"" in text(en), text(en))
        Assertions.assertFalse("translation:" in text(en), text(en))
    }

    @Test
    fun `a flat key goes to the files of the namespace selected`() = myFixture.runWithConfig(Config(flatKeys = true, defaultNs = "common")) {
        val en = addFileToProject("locales/en/common.json", """{"app.title": "App"}""").virtualFile

        create(Config(flatKeys = true, defaultNs = "common"), "app.subtitle", "en", "Subtitle")

        Assertions.assertTrue("\"app.subtitle\"" in text(en), text(en))
        Assertions.assertFalse("common:" in text(en), text(en))
    }
}
