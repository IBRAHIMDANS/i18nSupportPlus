package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
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

    private fun findSources(vararg namespaces: String): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findSources(namespaces.toList(), project)
        }

    /** The per-namespace lookup is cached like the scan: every key of every feature asks it on each pass. */
    @Test
    fun findSources_reusesTheLookupWhenNothingChanged() {
        addFileToProject("assets/common.json", """{"menu":"Home"}""")

        val first = findSources("common")

        Assertions.assertEquals(1, first.size)
        Assertions.assertSame(first, findSources("common"), "the second call must be served from the cache")
        Assertions.assertTrue(findSources("auth").isEmpty(), "each namespace list gets its own answer")
    }

    @Test
    fun findSources_seesANamespaceFileAddedAfterTheFirstLookup() {
        addFileToProject("assets/common.json", """{"menu":"Home"}""")
        Assertions.assertTrue(findSources("auth").isEmpty())

        addFileToProject("assets/auth.json", """{"login":"Log in"}""")

        Assertions.assertEquals(1, findSources("auth").size, "a new file must invalidate the cached lookup")
    }

    @Test
    fun findSources_isRecomputedWhenTheConfigurationChanges() {
        addFileToProject("assets/common.json", """{"menu":"Home"}""")
        val first = findSources("common")

        myFixture.runWithConfig(Config(defaultNs = "other")) {
            Assertions.assertNotSame(first, findSources("common"), "a configuration change must drop the cached lookup")
        }
    }

    private fun findNamespaceFiles(vararg namespaces: String): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findNamespaceFiles(namespaces.toList(), project)
        }

    /** Asked by the annotator for every key naming a namespace, on each pass: cached like [findSources]. */
    @Test
    fun findNamespaceFiles_reusesTheLookupWhenNothingChanged() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        addFileToProject("locales/fr/common.json", """{"menu":"Accueil"}""")

        val first = findNamespaceFiles("common")

        Assertions.assertEquals(2, first.size)
        Assertions.assertSame(first, findNamespaceFiles("common"), "the second call must be served from the cache")
        Assertions.assertTrue(findNamespaceFiles("auth").isEmpty(), "each namespace list gets its own answer")
    }

    /** The two lookups share a cache but not their answers: `findSources` adds the configured sources. */
    @Test
    fun findNamespaceFiles_doesNotServeTheAnswerOfFindSources() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")

        val sources = findSources("common")

        Assertions.assertNotSame(sources, findNamespaceFiles("common"))
    }

    @Test
    fun findNamespaceFiles_seesANamespaceFileAddedAfterTheFirstLookup() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        Assertions.assertTrue(findNamespaceFiles("auth").isEmpty())

        addFileToProject("locales/en/auth.json", """{"login":"Log in"}""")

        Assertions.assertEquals(1, findNamespaceFiles("auth").size, "a new file must invalidate the cached lookup")
    }

    @Test
    fun findNamespaceFiles_seesAnEditMadeAfterTheFirstLookup() {
        val file = addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        Assertions.assertEquals(1, findNamespaceFiles("common").size)

        myFixture.openFileInEditor(file.virtualFile)
        myFixture.type(" ")

        val tree = ReadAction.compute<String?, RuntimeException> {
            findNamespaceFiles("common").single().tree?.value()?.takeIf { it.isValid }?.text
        }
        Assertions.assertNotNull(tree, "the cached tree must not survive an edit as an invalid element")
    }

    @Test
    fun findNamespaceFiles_isRecomputedWhenTheConfigurationChanges() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        val first = findNamespaceFiles("common")

        myFixture.runWithConfig(Config(defaultNs = "other")) {
            Assertions.assertNotSame(first, findNamespaceFiles("common"), "a configuration change must drop the cached lookup")
        }
    }

    // What drops the caches. The scan by file type is stamped with TranslationModificationTracker,
    // the technologies' sources with the project-wide PSI count: a keystroke in a component keeps the
    // former, any change to a translation file, the file structure or a JS/TS catalog drops what
    // depends on it.

    private fun edit(file: PsiFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(file)!!.setText(text)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    @Test
    fun typingInAComponentKeepsTheScannedSources() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        val component = addFileToProject("src/Home.tsx", "export const Home = () => t('common:menu');")
        val all = findAllSources().single()
        val named = findSources("common").single()
        val namespaceFile = findNamespaceFiles("common").single()

        edit(component, "export const Home = () => t('common:menu') + 'x';")

        Assertions.assertSame(all, findAllSources().single(), "the scan must survive a keystroke in a component")
        Assertions.assertSame(named, findSources("common").single(), "the namespace lookup must survive it too")
        Assertions.assertSame(namespaceFile, findNamespaceFiles("common").single())
    }

    @Test
    fun editingATranslationFileDropsTheScannedSources() {
        val file = addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        val named = findSources("common").single()

        edit(file, """{"menu":"Home","title":"Title"}""")

        val after = findSources("common").single()
        Assertions.assertNotSame(named, after, "an edited translation file must be read again")
        val text = ReadAction.compute<String?, RuntimeException> { after.tree?.value()?.text }
        Assertions.assertTrue(text!!.contains("title"), "the new content must be the one served")
    }

    @Test
    fun deletingATranslationFileDropsIt() {
        addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        val french = addFileToProject("locales/fr/common.json", """{"menu":"Accueil"}""")
        Assertions.assertEquals(2, findSources("common").size)

        WriteCommandAction.runWriteCommandAction(project) { french.virtualFile.delete(this) }

        Assertions.assertEquals(1, findSources("common").size)
        Assertions.assertEquals(1, findAllSources().size)
    }

    @Test
    fun renamingATranslationFileMovesItToItsNewNamespace() {
        val file = addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        Assertions.assertEquals(1, findNamespaceFiles("common").size)

        WriteCommandAction.runWriteCommandAction(project) { file.virtualFile.rename(this, "auth.json") }

        Assertions.assertTrue(findNamespaceFiles("common").isEmpty(), "the old namespace must lose the file")
        Assertions.assertEquals(1, findNamespaceFiles("auth").size, "the new namespace must find it")
    }

    /** A TS catalog is a JS/TS file: any keystroke may edit it, so its sources are read again. */
    @Test
    fun editingATsCatalogIsSeen() {
        val catalog = addFileToProject(
            "src/i18n/translations.ts",
            """
            export const translations = {
              en: { common: { cancel: 'Cancel' } },
            } as const;
            """.trimIndent()
        )
        val before = findAllSources().size

        edit(catalog, """
            export const translations = {
              en: { common: { cancel: 'Cancel' } },
              fr: { common: { cancel: 'Annuler' } },
            } as const;
        """.trimIndent())

        Assertions.assertEquals(before + 1, findAllSources().size, "the locale added to the catalog must be found")
    }
}
