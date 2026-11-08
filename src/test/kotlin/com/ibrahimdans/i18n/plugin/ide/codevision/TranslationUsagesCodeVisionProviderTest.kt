package com.ibrahimdans.i18n.plugin.ide.codevision

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
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

    /** The index gives the exact count: no cap, a key called 104 times reads so. */
    @Test
    fun aKeyUsedManyTimesShowsItsExactCount() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        val calls = (List(104) { "t('common:menu.home')" } + List(99) { "t('common:menu.about')" }).joinToString()
        addFileToProject("src/App.js", "export const a = (t) => [$calls];")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("104 usages", labels["home"])
        assertEquals("99 usages", labels["about"])
    }

    /** `t(`common:status.${kind}`)` names no key, yet may reach every `status.*`. */
    @Test
    fun aKeyReachedByADynamicKeyIsShownAsSuch() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"status": {"ok": "OK"}}""")
        addFileToProject("src/App.js", "export const s = (t, kind) => t(`common:status.\${kind}`);")
        assertEquals("dynamic usage", labelsOf("locales/en/common.json")["ok"])
    }

    /** A call under a hook's key prefix names only the key's tail, and leaves no reference on it. */
    @Test
    fun aKeyUsedUnderAKeyPrefixIsCounted() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"profile": {"name": "Name"}}""")
        addFileToProject(
            "src/Profile.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const P = () => { const { t } = useTranslation('common', { keyPrefix: 'profile' }); return t('name'); };"
        )
        assertEquals("1 usage", labelsOf("locales/en/common.json")["name"])
    }

    /** A call writing the namespace and one under a hook declaring it are the same key's two usages. */
    @Test
    fun aKeyCalledWithAndWithoutItsNamespaceCountsEveryCall() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const a = (t) => t('common:menu.home');")
        addFileToProject(
            "src/Menu.tsx",
            "import { useTranslation } from 'react-i18next';\n" +
                "export const M = () => { const { t } = useTranslation('common'); return t('menu.home'); };"
        )
        assertEquals("2 usages", labelsOf("locales/en/common.json")["home"])
    }

    /** `t(KEYS.save)` writes the key in the constant, where its one usage is found. */
    @Test
    fun aKeyPassedThroughAConstantIsCounted() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"save": "Save", "far": "Far"}}""")
        addFileToProject("src/Keys.ts", "export const KEYS = { save: 'common:menu.save' } as const;\nexport const FAR = 'common:menu.far';")
        addFileToProject("src/Save.tsx", "import { KEYS, FAR } from './Keys';\nexport const S = (t) => [t(KEYS.save), t(FAR)];")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("1 usage", labels["save"])
        assertEquals("1 usage", labels["far"])
    }

    /** A key the project keeps reads "kept", not "no usages": nothing in the code names it. */
    @Test
    fun aKeptKeyIsShownAsSuch() = myFixture.runWithConfig(Config(defaultNs = "translation", keptKeys = "errors.*")) {
        addFileToProject("locales/en/common.json", """{"errors": {"timeout": "Timed out"}, "menu": {"home": "Home"}}""")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("kept (used outside the code)", labels["timeout"])
        assertEquals("no usages", labels["home"])
    }

    /**
     * The JSON plugin's name reference of a same-named property — the same key in another locale,
     * or an unrelated `title` elsewhere — is not a use by the code.
     */
    @Test
    fun aSameNamedPropertyOfAnotherJsonFileIsNotAUsage() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil", "about": "À propos"}}""")
        addFileToProject("config/site.json", """{"about": "unrelated"}""")
        addFileToProject("src/App.js", "export const a = (t) => t('common:menu.home');")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("1 usage", labels["home"])
        assertEquals("no usages", labels["about"])
    }

    /** vue-i18n: a key only linked from the other locale's file (`"@:common.save"`) is used, not "no usages". */
    @Test
    fun aKeyLinkedOnlyFromAnotherLocaleIsCounted() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "vue", rootDirectory = "", preset = "vue-i18n")))
    ) {
        addFileToProject("locales/en.json", """{"common": {"save": "Save", "orphan": "Orphan"}, "button": "Save"}""")
        addFileToProject("locales/fr.json", """{"common": {"save": "Enregistrer", "orphan": "Orphelin"}, "button": "Cliquer @:common.save"}""")
        val labels = labelsOf("locales/en.json")
        assertEquals("1 usage", labels["save"])
        assertEquals("no usages", labels["orphan"])
    }

    /** vue-i18n: a key only linked from another key of the same file is used. */
    @Test
    fun aKeyLinkedOnlyFromTheSameFileIsCounted() = myFixture.runWithConfig(
        Config(modules = listOf(ModuleConfig(name = "vue", rootDirectory = "", preset = "vue-i18n")))
    ) {
        addFileToProject("locales/en.json", """{"common": {"save": "Save", "orphan": "Orphan"}, "button": "Click @:common.save"}""")
        val labels = labelsOf("locales/en.json")
        assertEquals("1 usage", labels["save"])
        assertEquals("no usages", labels["orphan"])
    }

    /** i18next: a key only nested from another namespace's file (`"$t(common:save) now"`) is used. */
    @Test
    fun aKeyNestedOnlyFromAnotherNamespaceIsCounted() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        addFileToProject("locales/en/common.json", """{"save": "Save", "orphan": "Orphan"}""")
        addFileToProject("locales/en/translation.json", """{"button": "${'$'}t(common:save) now"}""")
        val labels = labelsOf("locales/en/common.json")
        assertEquals("1 usage", labels["save"])
        assertEquals("no usages", labels["orphan"])
    }

    /** A catalogue of 500 keys: every key gets its count, the index being asked once for the file. */
    @Test
    fun everyKeyOfALargeFileShowsItsCount() = myFixture.runWithConfig(Config(defaultNs = "translation")) {
        val keys = (0 until 500).joinToString { "\"k$it\": \"v\"" }
        addFileToProject("locales/en/common.json", "{$keys}")
        addFileToProject("src/App.js", "export const a = (t) => [" + (0 until 500 step 2).joinToString { "t('common:k$it')" } + "];")
        val labels = labelsOf("locales/en/common.json")
        assertEquals(500, labels.size)
        assertEquals((0 until 500).associate { "k$it" to if (it % 2 == 0) "1 usage" else "no usages" }, labels)
    }

    @Test
    fun aJsonFileThatIsNotATranslationShowsNothing() {
        addFileToProject("package.json", """{"name": "app"}""")
        assertTrue(labelsOf("package.json").isEmpty())
    }
}
