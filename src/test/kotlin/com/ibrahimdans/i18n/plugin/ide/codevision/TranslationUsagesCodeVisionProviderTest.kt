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

    /** Past [TranslationUsagesCodeVisionProvider.MAX_USAGES], the search stops and the label says so. */
    @Test
    fun aKeyUsedPastTheLimitShowsACappedLabel() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val max = TranslationUsagesCodeVisionProvider.MAX_USAGES
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        val calls = (List(max + 5) { "t('common:menu.home')" } + List(max) { "t('common:menu.about')" }).joinToString()
        addFileToProject("src/App.js", "export const a = (t) => [$calls];")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("$max+ usages", labels["home"])
        assertEquals("$max usages", labels["about"])
    }

    /** `t(`common:status.${kind}`)` names no key, yet may reach every `status.*`. */
    @Test
    fun aKeyReachedByADynamicKeyIsShownAsSuch() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"status": {"ok": "OK"}}""")
        addFileToProject("src/App.js", "export const s = (t, kind) => t(`common:status.\${kind}`);")
        assertEquals("dynamic usage", labelsOf("locales/en/common.json")["ok"])
    }

    /** A key the project keeps reads "kept", not "no usages": nothing in the code names it. */
    @Test
    fun aKeptKeyIsShownAsSuch() = myFixture.runWithConfig(Config(defaultNs = "translation", keptKeys = "errors.*")) {
        addFileToProject("locales/en/common.json", """{"errors": {"timeout": "Timed out"}, "menu": {"home": "Home"}}""")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("kept (used outside the code)", labels["timeout"])
        assertEquals("no usages", labels["home"])
    }

    @Test
    fun aFilePastTheKeyLimitShowsNothing()= myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val keys = (0..TranslationUsagesCodeVisionProvider.MAX_KEYS).joinToString { "\"k$it\": \"v\"" }
        addFileToProject("locales/en/common.json", "{$keys}")
        assertTrue(labelsOf("locales/en/common.json").isEmpty())
    }

    @Test
    fun aJsonFileThatIsNotATranslationShowsNothing() {
        addFileToProject("package.json", """{"name": "app"}""")
        assertTrue(labelsOf("package.json").isEmpty())
    }
}
