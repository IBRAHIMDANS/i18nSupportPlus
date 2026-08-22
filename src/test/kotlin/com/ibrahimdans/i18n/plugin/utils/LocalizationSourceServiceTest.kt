package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Covers the project-level cache on [LocalizationSourceService.findAllSources].
 *
 * The annotator, completion, folding, inlay hints and gutter icons all call it on every
 * highlighting pass, so it must hit the cache when nothing moved — and, more importantly,
 * never serve a stale scan when a file or the configuration changed.
 */
class LocalizationSourceServiceTest : PlatformBaseTest() {

    private fun findAllSources(): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project)
        }

    @Test
    fun findAllSources_reusesTheScanWhenNothingChanged() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")

        val first = findAllSources()
        val second = findAllSources()

        Assertions.assertFalse(first.isEmpty(), "the fixture file must be found")
        Assertions.assertSame(first, second, "the second call must be served from the cache")
    }

    @Test
    fun findAllSources_seesAFileAddedAfterTheFirstScan() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        val before = findAllSources()

        addFileToProject("locales/fr/common.json", """{"menu":{"home":"Accueil"}}""")
        val after = findAllSources()

        Assertions.assertEquals(before.size + 1, after.size, "a new locale file must invalidate the cache")
    }

    @Test
    fun findAllSources_seesAnEditMadeAfterTheFirstScan() {
        val file = addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        Assertions.assertFalse(findAllSources().isEmpty())

        myFixture.openFileInEditor(file.virtualFile)
        myFixture.type(" ")

        val resolved = ReadAction.compute<String?, RuntimeException> {
            findAllSources().firstOrNull()?.tree?.value()?.text
        }
        Assertions.assertNotNull(resolved, "the cached tree must not survive an edit as an invalid element")
    }

    @Test
    fun findAllSources_isRecomputedWhenTheConfigurationChanges() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        Assertions.assertFalse(findAllSources().isEmpty())

        // runWithConfig restores the previous settings: the light fixture project is shared
        // across the tests of this class, so a leaked translations root would blank out the
        // scan of every test running after this one.
        myFixture.runWithConfig(Config(translationsRoot = "somewhere/else")) {
            Assertions.assertTrue(
                findAllSources().isEmpty(),
                "a new translations root must invalidate the cache"
            )
        }
    }

    /**
     * The tool window reaches [LocalizationSourceService.findAllSources] from a pooled thread
     * without holding a read action — `TreeViewPanel`, `TableViewPanel` and `TranslationStatsPanel`
     * all do. Validating the cached scan touches the PSI (`isValid`), so the service has to open
     * the read action itself; otherwise the platform logs
     * "Read access is allowed from inside read-action only" as a SEVERE naming the plugin.
     *
     * The other cases here wrap every call in `ReadAction.compute`, which is exactly why none of
     * them caught it. This one deliberately does not: under the test logger that SEVERE fails the
     * test, so it pins the fix rather than the symptom.
     *
     * The second call is the one that matters — the first only populates the cache.
     */
    @Test
    fun findAllSources_servesTheCachedScanFromAPooledThreadWithoutAReadAction() {
        addFileToProject("locales/en/common.json", """{"menu":{"home":"Home"}}""")
        findAllSources()

        val fromPool = ApplicationManager.getApplication()
            .executeOnPooledThread<List<LocalizationSource>> {
                project.service<LocalizationSourceService>().findAllSources(project)
            }
            .get(30, TimeUnit.SECONDS)

        Assertions.assertFalse(fromPool.isEmpty(), "the cached scan must still be served")
    }
}
