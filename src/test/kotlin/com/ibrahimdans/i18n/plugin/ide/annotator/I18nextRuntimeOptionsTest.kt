package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.quickfix.CreateKeyDialogQuickFix
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * i18next's options read at runtime: `returnObjects: true` asks for the object under the key,
 * a default value is what the screen shows while the key is missing, and `t(['a', 'b'])` shows
 * the first key that exists.
 */
class I18nextRuntimeOptionsTest : PlatformBaseTest() {

    private val translations = """{"menu": {"items": ["Home", "About"], "title": "Menu"}, "generic": "Something went wrong"}"""

    /** The descriptions of [code]'s highlights, by severity. */
    private fun highlightsIn(code: String): Map<HighlightSeverity, List<String?>> {
        myFixture.addFileToProject("locales/en/test.json", translations)
        myFixture.configureByText("Menu.tsx", code)
        return myFixture.doHighlighting().groupBy({ it.severity }, { it.description })
    }

    private fun errorsIn(code: String) = highlightsIn(code)[HighlightSeverity.ERROR].orEmpty()

    @Test
    fun `returnObjects true reads the object without a warning`() {
        Assertions.assertEquals(emptyList<String?>(), errorsIn("export const a = (t) => t('test:menu.items', { returnObjects: true });"))
    }

    @Test
    fun `returnObjects false keeps the reference to object`() {
        Assertions.assertEquals(listOf("Reference to object"), errorsIn("export const a = (t) => t('test:menu', { returnObjects: false });"))
    }

    @Test
    fun `returnObjects from a variable keeps the reference to object`() {
        Assertions.assertEquals(listOf("Reference to object"), errorsIn("export const a = (t, all) => t('test:menu', { returnObjects: all });"))
    }

    @Test
    fun `a default value second makes a missing key a weak warning`() {
        val highlights = highlightsIn("export const a = (t) => t('test:menu.subtitle', 'Default text');")
        Assertions.assertEquals(null, highlights[HighlightSeverity.ERROR])
        Assertions.assertEquals(listOf("Key missing, default value shown"), highlights[HighlightSeverity.WEAK_WARNING])
    }

    @Test
    fun `a defaultValue option makes a missing key a weak warning`() {
        val highlights = highlightsIn("export const a = (t) => t('test:menu.subtitle', { defaultValue: 'Default text' });")
        Assertions.assertEquals(null, highlights[HighlightSeverity.ERROR])
        Assertions.assertEquals(listOf("Key missing, default value shown"), highlights[HighlightSeverity.WEAK_WARNING])
    }

    @Test
    fun `options after a default value are read`() {
        Assertions.assertEquals(emptyList<String?>(), errorsIn("export const a = (t) => t('menu.items', 'Items', { ns: 'test', returnObjects: true });"))
    }

    @Test
    fun `create key proposes the default value`() {
        myFixture.addFileToProject("locales/en/test.json", translations)
        myFixture.configureByText("Menu.tsx", "export const a = (t) => t('test:menu.sub<caret>title', { defaultValue: 'Default text' });")
        val proposed = mutableListOf<Pair<String, String?>>()
        CreateKeyDialogQuickFix.opener = { _, key, _, value -> proposed += key.source to value }
        myFixture.launchActionAndWait(myFixture.filterAvailableIntentions(createKey).first { it.text == createKey })
        Assertions.assertEquals(listOf("test:menu.subtitle" to "Default text"), proposed)
    }

    @Test
    fun `an array of keys resolves when one of them exists`() {
        Assertions.assertEquals(emptyList<String?>(), errorsIn("export const a = (t) => t(['test:error.specific', 'test:generic']);"))
    }

    @Test
    fun `an array of keys is unresolved when none exists`() {
        Assertions.assertEquals(
            listOf("Unresolved key", "Unresolved key"),
            errorsIn("export const a = (t) => t(['test:error.specific', 'test:error.other']);")
        )
    }

    @AfterEach
    fun restoreDialog() {
        CreateKeyDialogQuickFix.opener = { project, key, caller, value ->
            TranslationDialog(project, key, Mode.CREATE, caller, initialValue = value).show()
        }
    }

    private val createKey = "Create i18n key"
}
