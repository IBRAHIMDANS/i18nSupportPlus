package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the rule that decides whether a VFS change is worth reloading the tool window for.
 *
 * The case that matters most is the negative one: a project writes files constantly, and a
 * reload re-reads the whole PSI tree of every translation file. A change outside the
 * translations must cost a set lookup and nothing else.
 */
class TranslationSourceMatcherTest : PlatformBaseTest() {

    private fun matcherSeededWith(vararg files: String): TranslationSourceMatcher {
        files.forEach { addFileToProject(it, """{"hello": "Bonjour"}""") }
        return TranslationSourceMatcher(project).apply { rememberDisplayedSources() }
    }

    @Test
    fun displayedTranslationFileIsPartOfTheSnapshot() {
        val file = addFileToProject("locales/fr/common.json", """{"hello": "Bonjour"}""").virtualFile
        val matcher = TranslationSourceMatcher(project)

        matcher.rememberDisplayedSources()

        assertTrue(
            matcher.displayedSourcePaths().contains(file.path),
            "The scan feeding the tool window must feed the rule too"
        )
    }

    @Test
    fun editingDisplayedTranslationFileTriggersReload() {
        val matcher = matcherSeededWith("locales/fr/common.json")
        val file = myFixture.findFileInTempDir("locales/fr/common.json")

        assertTrue(matcher.matches(file, isContentChange = true))
    }

    @Test
    fun editingFileOutsideTranslationsTriggersNothing() {
        val matcher = matcherSeededWith("locales/fr/common.json")
        val source = addFileToProject("src/App.tsx", "export const App = () => null").virtualFile

        assertFalse(matcher.matches(source, isContentChange = true), "A source file is not a translation")
        assertFalse(matcher.matches(source, isContentChange = false), "…not even when created")
    }

    @Test
    fun editingUndisplayedJsonTriggersNothing() {
        val matcher = matcherSeededWith("locales/fr/common.json")
        // package.json is json, and is written on every dependency change.
        val other = addFileToProject("package.json", """{"name": "app"}""").virtualFile

        assertFalse(
            matcher.matches(other, isContentChange = true),
            "The plugin's own formats are used for everything else too"
        )
    }

    @Test
    fun creatingUndisplayedJsonTriggersReload() {
        val matcher = matcherSeededWith("locales/fr/common.json")
        val added = addFileToProject("locales/en/common.json", """{"hello": "Hello"}""").virtualFile

        assertFalse(matcher.displayedSourcePaths().contains(added.path), "Not in the snapshot yet")
        assertTrue(
            matcher.matches(added, isContentChange = false),
            "A structural change is the only way a new translation file gets in"
        )
    }

    @Test
    fun directoryTriggersNothing() {
        val matcher = matcherSeededWith("locales/fr/common.json")
        val dir = myFixture.findFileInTempDir("locales/fr")

        assertFalse(matcher.matches(dir, isContentChange = false))
    }

    @Test
    fun emptyBatchTriggersNothing() {
        val matcher = matcherSeededWith("locales/fr/common.json")

        assertFalse(matcher.matchesAny(emptyList()))
    }
}
