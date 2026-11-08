package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * *Export Translations* computes its files in the background, then writes them: an export runs to the
 * file it names, and an export cancelled while it computes writes nothing and shows nothing.
 */
class ExportTranslationsActionTest : PlatformBaseTest() {

    @TempDir
    lateinit var out: File

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    private fun translations() {
        addFileToProject("locales/en/common.json", """{"button": {"save": "Save", "cancel": "Cancel"}}""")
        addFileToProject("locales/fr/common.json", """{"button": {"save": "Enregistrer"}}""")
    }

    /** Runs [plan] to its end and returns the message it showed. */
    private fun export(plan: (ProgressIndicator) -> ExportTranslationsAction.Export): String {
        var shown: String? = null
        TestDialogManager.setTestDialog { message -> shown = message; Messages.OK }
        val job = ExportTranslationsAction.run(project, plan)
        PlatformTestUtil.waitWithEventsDispatching("the export never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return shown ?: throw AssertionError("no message shown")
    }

    @Test
    fun aCsvExportWritesEveryKeyOfEveryLocaleToTheChosenFile() {
        translations()
        val target = File(out, "translations.csv")

        val message = export { ExportTranslationsAction.csv(project, null, target, missingOnly = false, it) }

        val csv = target.readText()
        Assertions.assertTrue(csv.lines().first().contains("en") && csv.lines().first().contains("fr"), csv)
        Assertions.assertTrue("Save" in csv && "Enregistrer" in csv && "Cancel" in csv, csv)
        Assertions.assertTrue(target.absolutePath in message, message)
    }

    @Test
    fun aMissingOnlyCsvExportKeepsOnlyTheKeysLeftToTranslate() {
        translations()
        val target = File(out, "translations-missing.csv")

        export { ExportTranslationsAction.csv(project, null, target, missingOnly = true, it) }

        val csv = target.readText()
        Assertions.assertTrue("Cancel" in csv, csv)
        Assertions.assertFalse("Enregistrer" in csv, csv)
    }

    @Test
    fun anXliffExportWritesOneFilePerTargetLocale() {
        translations()

        export { ExportTranslationsAction.xliff(project, null, out, it) }

        Assertions.assertEquals(listOf("translations.fr.xlf"), out.list()!!.sorted())
        Assertions.assertTrue("Enregistrer" in File(out, "translations.fr.xlf").readText())
    }

    @Test
    fun anExportCancelledWhileItComputesWritesNothingAndShowsNothing() {
        translations()
        val target = File(out, "translations.csv")
        val started = CountDownLatch(1)
        TestDialogManager.setTestDialog { message -> throw AssertionError("Unexpected modal: $message") }

        // A plan that ends only once cancelled, checking for it as the translation loader does.
        fun endless(@Suppress("UNUSED_PARAMETER") indicator: ProgressIndicator): ExportTranslationsAction.Export {
            started.countDown()
            while (true) {
                ProgressManager.checkCanceled()
                Thread.onSpinWait()
            }
        }

        val job = ExportTranslationsAction.run(project, ::endless)
        PlatformTestUtil.waitWithEventsDispatching("the export never started", { started.count == 0L }, 30)
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the cancelled export never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        Assertions.assertTrue(job.isCancelled, "the job ends cancelled")
        Assertions.assertFalse(target.exists(), "no file written")
    }
}
