package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Root deduction for the setup wizard.
 *
 * The wizard used to read the grand-parent of every file it found, assuming a fixed
 * `locales/<locale>/<ns>.json` depth. On a flat `locales/fr.json` that grand-parent is null,
 * so every candidate was dropped, `translationsRoot` was left untouched and the wizard wrote
 * **nothing** — right after telling the user it had found 2 files and offering *Apply*.
 */
class TranslationRootDetectorTest : PlatformBaseTest() {

    @Test
    fun flatLayoutYieldsItsFolder() {
        Assertions.assertEquals(
            "locales",
            TranslationRootDetector.detect(listOf("locales/fr.json", "locales/en.json")),
            "the layout the wizard used to drop entirely"
        )
    }

    @Test
    fun nestedLayoutStopsAboveTheLocaleFolder() {
        Assertions.assertEquals(
            "locales",
            TranslationRootDetector.detect(listOf("locales/en/common.json", "locales/fr/common.json"))
        )
    }

    @Test
    fun nestedLayoutKeepsItsLeadingFolders() {
        Assertions.assertEquals(
            "src/locales",
            TranslationRootDetector.detect(listOf("src/locales/en/ns.json"))
        )
    }

    /** GetText nests one level deeper: `locales/fr/LC_MESSAGES/messages.po`. */
    @Test
    fun getTextLayoutSkipsTheMessagesFolder() {
        Assertions.assertEquals(
            "locales",
            TranslationRootDetector.detect(
                listOf("locales/fr/LC_MESSAGES/messages.po", "locales/en/LC_MESSAGES/messages.po")
            )
        )
    }

    /** Flat and nested files in one project must still agree on the folder above them. */
    @Test
    fun mixedLayoutsAgreeOnTheirCommonRoot() {
        Assertions.assertEquals(
            "locales",
            TranslationRootDetector.detect(listOf("locales/fr.json", "locales/en/common.json"))
        )
    }

    /**
     * The invariant no test covered: `translationsRoot` is read back as `"$basePath/$root"`,
     * so an absolute path would produce `/project//home/user/project/locales` and match nothing.
     */
    @Test
    fun theDerivedRootIsRelative() {
        val root = TranslationRootDetector.detect(listOf("src/locales/en.json"))

        Assertions.assertEquals("src/locales", root)
        Assertions.assertFalse(root!!.startsWith("/"), "the stored root must stay project-relative")
    }

    /** A folder named after a framework must not be mistaken for a locale and stripped. */
    @Test
    fun conventionalFolderNamesAreNotLocales() {
        Assertions.assertEquals("lang", TranslationRootDetector.detect(listOf("lang/fr.json")))
        Assertions.assertEquals("i18n", TranslationRootDetector.detect(listOf("i18n/en.json")))
    }

    @Test
    fun noFilesYieldNoRoot() {
        Assertions.assertNull(TranslationRootDetector.detect(emptyList()))
    }

    /**
     * A catalogue sitting at the project root leaves nothing to configure: an empty root means
     * "no root directory" to the scanner, which is not what the summary would be claiming.
     */
    @Test
    fun aFileAtTheProjectRootYieldsNoRoot() {
        Assertions.assertNull(TranslationRootDetector.detect(listOf("en.json")))
        Assertions.assertNull(TranslationRootDetector.detect(listOf("fr/common.json")))
    }

    /**
     * Roots that disagree widen to their common prefix rather than dropping one of them —
     * wider than ideal on a monorepo, but both catalogues stay inside it.
     */
    @Test
    fun disagreeingRootsWidenToTheirCommonPrefix() {
        Assertions.assertEquals(
            "apps",
            TranslationRootDetector.detect(listOf("apps/web/locales/en.json", "apps/api/locales/en.json"))
        )
    }

    /**
     * The widening above is invisible in [TranslationRootDetector.detect]'s answer, which is how
     * the summary came to show a guessed `apps` in the same tone as a value read off disk.
     * [TranslationRootDetector.candidates] is what lets the caller say so.
     */
    @Test
    fun candidatesListEveryFolderTheCommonPrefixHadToSwallow() {
        Assertions.assertEquals(
            listOf("apps/api/locales", "apps/web/locales"),
            TranslationRootDetector.candidates(listOf("apps/web/locales/en.json", "apps/api/locales/en.json")),
            "sorted, so the summary never depends on the order the file system returned"
        )
    }

    @Test
    fun candidatesCollapseFilesSharingOneRoot() {
        Assertions.assertEquals(
            listOf("locales"),
            TranslationRootDetector.candidates(listOf("locales/fr.json", "locales/en/common.json"))
        )
    }

    @Test
    fun candidatesDropAFileSittingAtTheProjectRoot() {
        Assertions.assertEquals(
            listOf("locales"),
            TranslationRootDetector.candidates(listOf("en.json", "locales/fr.json")),
            "a file with no folder above it names no candidate"
        )
    }

    @Test
    fun candidatesAreEmptyWithoutFiles() {
        Assertions.assertEquals(emptyList<String>(), TranslationRootDetector.candidates(emptyList()))
    }
}
