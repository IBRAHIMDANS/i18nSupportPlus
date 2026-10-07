package com.ibrahimdans.i18n.plugin.ide.codevision

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationUsagesCodeVisionProviderTest : PlatformBaseTest() {

    /** The usage label of each key of the file at [path], keyed by the text the entry sits on. */
    private fun labelsOf(path: String): Map<String, String> {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        val entries = ReadAction.compute<List<Pair<String, String>>, RuntimeException> {
            TranslationUsagesCodeVisionProvider().computeForEditor(myFixture.editor, myFixture.file).map { (range, entry) ->
                myFixture.editor.document.getText(range).substringBefore(':').trim('"') to (entry as ClickableTextCodeVisionEntry).text
            }
        }
        return entries.toMap()
    }

    @Test
    fun usedAndUnusedKeysAreCounted() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        addFileToProject("src/App.js", "export const a = (t) => [t('common:menu.home'), t('common:menu.home')];")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("2 usages", labels["home"])
        assertEquals("no usages", labels["about"])
    }

    /** `t(`common:status.${kind}`)` names no key, yet may reach every `status.*`. */
    @Test
    fun aKeyReachedByADynamicKeyIsShownAsSuch() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"status": {"ok": "OK"}}""")
        addFileToProject("src/App.js", "export const s = (t, kind) => t(`common:status.\${kind}`);")
        assertEquals("dynamic usage", labelsOf("locales/en/common.json")["ok"])
    }

    @Test
    fun aJsonFileThatIsNotATranslationShowsNothing() {
        addFileToProject("package.json", """{"name": "app"}""")
        assertTrue(labelsOf("package.json").isEmpty())
    }
}
