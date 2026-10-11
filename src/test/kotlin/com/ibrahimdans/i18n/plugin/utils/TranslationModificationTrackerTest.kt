package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What moves [TranslationModificationTracker], and so drops the scan of the translation sources.
 *
 * PHP is a translation type since Laravel's `lang/<locale>/<file>.php`: a keystroke in a controller must
 * leave the scan alone, while any change that can alter what the scan reads must still drop it.
 */
class TranslationModificationTrackerTest : PlatformBaseTest() {

    private val messages = """
        <?php

        return [
            'welcome' => 'Welcome',
        ];
    """.trimIndent()

    private val controller = """
        <?php

        class HomeController
        {
            public function index() { return __('messages.welcome'); }
        }
    """.trimIndent()

    private fun count(): Long = TranslationModificationTracker.getInstance(project).modificationCount

    private fun findAllSources(): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findAllSources(project)
        }

    /** [times] keystrokes at the end of [file], each committed as the editor does. */
    private fun type(file: PsiFile, times: Int) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        repeat(times) {
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "x") }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun insertBefore(file: PsiFile, anchor: String, text: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.indexOf(anchor), text)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    @Test
    fun typingInAPhpControllerLeavesTheTrackerAlone() {
        addFileToProject("lang/en/messages.php", messages)
        val file = addFileToProject("app/Http/Controllers/HomeController.php", controller)
        val sources = findAllSources()
        val before = count()

        type(file, KEYSTROKES)

        Assertions.assertEquals(0, count() - before, "a keystroke in a controller must not move the tracker")
        // The list itself is put together again (the technologies' sources follow every keystroke),
        // the sources the file index found must be the cached ones.
        val after = findAllSources()
        Assertions.assertEquals(sources.size, after.size)
        Assertions.assertTrue(sources.zip(after).all { (a, b) -> a === b }, "the scan must survive keystrokes in a controller")
    }

    @Test
    fun typingInAPhpFileNamedAfterALocaleOutsideALocaleDirectoryLeavesTheTrackerAlone() {
        // `app/en.php` is read by the full scan (its stem looks like a locale), but never as a tree.
        val file = addFileToProject("app/en.php", messages)
        val before = count()

        insertBefore(file, "];", "    'title' => 'Title',\n")

        Assertions.assertEquals(0, count() - before)
    }

    @Test
    fun editingALaravelTranslationFileMovesTheTracker() {
        val file = addFileToProject("lang/en/messages.php", messages)
        val before = count()

        insertBefore(file, "];", "    'title' => 'Title',\n")

        Assertions.assertTrue(count() > before, "an edited translation file must move the tracker")
    }

    @Test
    fun aPhpFileStartingToReturnAnArrayIsSeen() {
        val file = addFileToProject("lang/en/messages.php", "<?php\n")
        Assertions.assertNull(findAllSources().single { it.name == "messages.php" }.tree, "no array, no tree")

        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(file)!!.setText(messages)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        Assertions.assertNotNull(findAllSources().single { it.name == "messages.php" }.tree, "the array must now be read")
    }

    @Test
    fun creatingALaravelTranslationFileMovesTheTracker() {
        addFileToProject("lang/en/messages.php", messages)
        val before = count()

        addFileToProject("lang/fr/messages.php", messages)

        Assertions.assertTrue(count() > before)
        Assertions.assertEquals(2, findAllSources().count { it.name == "messages.php" })
    }

    @Test
    fun deletingALaravelTranslationFileMovesTheTracker() {
        addFileToProject("lang/en/messages.php", messages)
        val french = addFileToProject("lang/fr/messages.php", messages)
        Assertions.assertEquals(2, findAllSources().count { it.name == "messages.php" })
        val before = count()

        WriteCommandAction.runWriteCommandAction(project) { french.virtualFile.delete(this) }

        Assertions.assertTrue(count() > before)
        Assertions.assertEquals(1, findAllSources().count { it.name == "messages.php" })
    }

    @Test
    fun movingAPhpFileIntoALocaleDirectoryMakesItATranslationFile() {
        addFileToProject("lang/en/messages.php", messages)
        val file = addFileToProject("app/auth.php", messages)
        Assertions.assertTrue(findAllSources().none { it.name == "auth.php" })
        val before = count()

        WriteCommandAction.runWriteCommandAction(project) {
            file.virtualFile.move(this, file.virtualFile.parent.parent.findFileByRelativePath("lang/en")!!)
        }

        Assertions.assertTrue(count() > before)
        Assertions.assertNotNull(findAllSources().single { it.name == "auth.php" }.tree)
    }

    @Test
    fun movingATranslationFileOutOfItsLocaleDirectoryDropsIt() {
        val file = addFileToProject("lang/en/auth.php", messages)
        addFileToProject("app/placeholder.php", "<?php\n")
        Assertions.assertNotNull(findAllSources().single { it.name == "auth.php" }.tree)
        val before = count()

        WriteCommandAction.runWriteCommandAction(project) {
            file.virtualFile.move(this, file.virtualFile.parent.parent.parent.findChild("app")!!)
        }

        Assertions.assertTrue(count() > before)
        Assertions.assertTrue(findAllSources().none { it.name == "auth.php" })
    }

    @Test
    fun editingJsonAndYamlTranslationFilesStillMovesTheTracker() {
        val json = addFileToProject("locales/en/common.json", """{"menu":"Home"}""")
        val yaml = addFileToProject("locales/fr/common.yml", "menu: Accueil\n")

        var before = count()
        type(json, 1)
        Assertions.assertTrue(count() > before, "JSON")

        before = count()
        type(yaml, 1)
        Assertions.assertTrue(count() > before, "YAML")
    }

    private companion object {
        const val KEYSTROKES = 20
    }
}
