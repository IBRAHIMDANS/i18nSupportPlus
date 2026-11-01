package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * i18next's `context` option: `t('friend', { context: gender })` reads `friend_male` or
 * `friend_female`. The key was reported unresolved in the code, and its variants as having no
 * reference in the translation file, with *Delete* one click away.
 */
class I18nextContextTest : PlatformBaseTest() {

    private val unusedMessage = "No reference to this key found in code"

    private fun errorsIn(code: String): List<String?> {
        myFixture.configureByText("Friend.tsx", code)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
    }

    private fun unusedIn(path: String): List<String> {
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        return myFixture.doHighlighting().filter { it.description == unusedMessage }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset).trim('"') }
    }

    @Test
    fun `a dynamic context resolves when the file holds variants only`() {
        myFixture.addFileToProject("locales/en/test.json", """{"people": {"friend_male": "He", "friend_female": "She"}}""")
        assertEquals(emptyList<String?>(), errorsIn("export const a = (t, g) => t('test:people.friend', { context: g });"))
    }

    @Test
    fun `a fixed context missing with no base key is unresolved`() {
        myFixture.addFileToProject("locales/en/test.json", """{"people": {"friend_male": "He"}}""")
        assertEquals(listOf("Unresolved key"), errorsIn("export const a = (t) => t('test:people.friend', { context: 'other' });"))
    }

    @Test
    fun `a fixed context falls back to the key itself`() {
        myFixture.addFileToProject("locales/en/test.json", """{"people": {"friend": "Friend", "friend_male": "He"}}""")
        assertEquals(emptyList<String?>(), errorsIn("export const a = (t) => t('test:people.friend', { context: 'other' });"))
    }

    @Test
    fun `no context option never reads a variant`() {
        myFixture.addFileToProject("locales/en/test.json", """{"people": {"friend_male": "He"}}""")
        assertEquals(listOf("Unresolved key"), errorsIn("export const a = (t) => t('test:people.friend');"))
    }

    @Test
    fun `a context variant and its plural forms are used by the call reading them`() {
        myFixture.addFileToProject(
            "locales/en/test.json",
            """{"people": {"friend_male_one": "He", "friend_male_other": "They", "friend_female": "She", "enemy": "Foe"}}"""
        )
        myFixture.addFileToProject("src/Friend.tsx", "export const a = (t, g, n) => t('test:people.friend', { context: g, count: n });")
        assertEquals(listOf("enemy"), unusedIn("locales/en/test.json"))
    }

    @Test
    fun `a fixed context uses its own variant only`() {
        myFixture.addFileToProject("locales/en/test.json", """{"people": {"friend_male": "He", "friend_female": "She"}}""")
        myFixture.addFileToProject("src/Friend.tsx", "export const a = (t) => t('test:people.friend', { context: 'male' });")
        assertEquals(listOf("friend_female"), unusedIn("locales/en/test.json"))
    }
}
