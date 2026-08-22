package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ApplicationManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * How the tool window actually calls this loader: from a pooled thread, holding no read action.
 * `TreeViewPanel`, `TableViewPanel` and `TranslationStatsPanel` (twice) all do, and they call
 * [TranslationDataLoader.discoverLocales] and [TranslationDataLoader.loadAllTranslations] one
 * after the other. Every other case in this package calls them from the test thread instead, so
 * nothing covered the path the IDE takes — which is how the read-action defect reached a release.
 *
 * Under the test logger the platform's "Read access is allowed from inside read-action only"
 * becomes a test failure, so this pins the contract: whatever these two touch, they open their
 * own read actions. Verified against a loader with that read action removed, where it fails on
 * exactly that message. It does not distinguish *how many* read actions they open — moving the walk in
 * `loadAllTranslations` from one read action per source to one for the batch keeps this green
 * either way; that change is about not letting a write land between two sources, which no
 * fixture can schedule deterministically.
 */
class TranslationDataLoaderThreadingTest : PlatformBaseTest() {

    @Test
    fun theToolWindowPathReadsFromAPooledThreadWithoutAReadAction() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil"}}""")

        val loaded = ApplicationManager.getApplication()
            .executeOnPooledThread<Pair<List<String>, Map<String, Map<String, String>>>> {
                TranslationDataLoader.discoverLocales(project) to
                        TranslationDataLoader.loadAllTranslations(project)
            }
            .get(30, TimeUnit.SECONDS)

        assertEquals(listOf("en", "fr"), loaded.first, "both locales must be discovered")
        assertEquals(
            mapOf("en" to "Home", "fr" to "Accueil"),
            loaded.second["common:menu.home"],
            "the whole batch must be loaded, not the first source only"
        )
    }
}
