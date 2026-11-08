package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.extensions.lang.js.JsLang
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFile
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * How [CodeUsageIndexService] keeps the code it read: once for every question, then file by file as
 * the files change, and whole again when the settings do.
 */
class CodeUsageIndexServiceTest : PlatformBaseTest() {

    private val index by lazy { IdeKeyUsageIndex(project) }
    private val service get() = CodeUsageIndexService.getInstance(project)
    private val settings get() = Settings.getInstance(project)

    private fun menu(): PsiFile = myFixture.addFileToProject("src/Menu.tsx", "export const home = () => i18n.t('menu.home');")
    private fun profile(): PsiFile = myFixture.addFileToProject("src/Profile.tsx", "export const name = () => i18n.t('profile.name');")

    private fun count(key: String): Int = index.usageCounts(listOf(key)).getValue(key)

    /** How many code files [block] made the index read. */
    private fun readsDuring(block: () -> Unit): Long {
        val before = service.filesRead()
        block()
        return service.filesRead() - before
    }

    @AfterEach
    fun deleteConfigFile() {
        val file = project.guessProjectDir()?.findChild(ProjectConfigFile.PATH) ?: return
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(file)?.let { ApplicationManager.getApplication().invokeAndWait { documents.reloadFromDisk(it) } }
        WriteAction.runAndWait<Exception> { file.delete(this) }
        settings.endTypingPause()
    }

    @Test
    fun `the code is read once for many questions`() {
        menu()
        profile()

        val first = readsDuring { Assertions.assertEquals(1, count("menu.home")) }
        val next = readsDuring {
            repeat(5) {
                Assertions.assertEquals(mapOf("menu.home" to 1, "profile.name" to 1), index.usageCounts(listOf("menu.home", "profile.name")))
                index.dynamicallyReached(listOf("menu.home", "profile.name"))
            }
        }

        Assertions.assertTrue(first >= 2, "both files read at the first question, read $first")
        Assertions.assertEquals(0, next)
    }

    @Test
    fun `an edited file is read again, and only it`() {
        val menu = menu()
        profile()
        count("menu.home")

        val reads = readsDuring {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = myFixture.getDocument(menu)
                document.insertString(document.textLength, "\nexport const back = () => i18n.t('menu.home');")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            Assertions.assertEquals(2, count("menu.home"))
        }

        Assertions.assertEquals(1, reads)
        Assertions.assertEquals(1, count("profile.name"))
    }

    @Test
    fun `a deleted file leaves the index`() {
        val menu = menu()
        profile()
        Assertions.assertEquals(1, count("menu.home"))

        WriteCommandAction.runWriteCommandAction(project) { menu.virtualFile.delete(this) }

        Assertions.assertEquals(0, count("menu.home"))
        Assertions.assertEquals(1, count("profile.name"))
    }

    /** A `git checkout` deletes a file and writes another at its path: the new one's usages count, the old one's do not. */
    @Test
    fun `a file created again at the path of an edited and deleted one is read`() {
        val menu = menu()
        Assertions.assertEquals(1, count("menu.home"))
        // Edited, then deleted before the next question: it is still waiting to be read again.
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.getDocument(menu)
            document.insertString(document.textLength, "\nexport const back = () => i18n.t('menu.home');")
            PsiDocumentManager.getInstance(project).commitDocument(document)
            menu.virtualFile.delete(this)
        }

        myFixture.addFileToProject("src/Menu.tsx", "export const about = () => i18n.t('menu.about');")

        Assertions.assertEquals(0, count("menu.home"))
        Assertions.assertEquals(1, count("menu.about"))
    }

    @Test
    fun `a moved file is found at its new place`() {
        val menu = menu()
        Assertions.assertEquals(1, count("menu.home"))
        val target = myFixture.tempDirFixture.findOrCreateDir("src/moved")

        WriteCommandAction.runWriteCommandAction(project) { menu.virtualFile.move(this, target) }

        val usage = index.usages("menu.home").single()
        Assertions.assertTrue(usage.path.endsWith("src/moved/Menu.tsx"), usage.path)
    }

    /** The places the index keeps are found again as the elements the code holds there. */
    @Test
    fun `a usage is found again as its literal`() {
        menu()

        val found = index.readUsages("menu.home") { it.text }

        Assertions.assertEquals(listOf("'menu.home'"), found)
    }

    @Test
    fun `a change of settings reads the code again`() {
        menu()
        profile()
        count("menu.home")

        val reads = readsDuring {
            myFixture.runWithConfig(Config(defaultNs = "common")) { Assertions.assertEquals(1, count("menu.home")) }
        }

        Assertions.assertTrue(reads >= 2, "every file read again, read $reads")
    }

    /** The files read are those a [Lang] says it reads: a language the plugin learns has its files read, with no list to edit. */
    @Test
    fun `the files read are those a language declares`() {
        menu()
        myFixture.addFileToProject("src/notes.txt", "i18n.t('menu.home')")

        Assertions.assertEquals(1, readsDuring { count("menu.home") }, "a text file is read by no language")

        val readsText = object : Lang by JsLang() {
            override fun indexes(type: FileType): Boolean = type == PlainTextFileType.INSTANCE
        }
        Extensions.LANG.point.registerExtension(readsText, testRootDisposable)
        service.forget()

        Assertions.assertEquals(2, readsDuring { count("menu.home") }, "the text file read once a language declares it")
    }

    /**
     * `i18n-check.json` open in the editor is read unsaved once the typing in it pauses ([Settings]).
     * A keystroke that leaves the settings as they were costs the index nothing.
     */
    @Test
    fun `a keystroke in the open config file that leaves the settings as they were reads no code`() {
        menu()
        profile()
        val document = openConfigFile()
        count("menu.home")

        val reads = readsDuring {
            edit(document, "{", "{ ")
            settings.endTypingPause()
            Assertions.assertEquals(1, count("menu.home"))
        }

        Assertions.assertEquals(0, reads)
    }

    /**
     * Typing `node_modules` into `excludedDirectories` is twelve keystrokes, each changing the settings: the
     * index reads the project once, after the pause in the typing, not once per keystroke as #926 measured.
     */
    @Test
    fun `a burst of keystrokes in the open config file reads the code once, after the pause`() {
        menu()
        profile()
        val document = openConfigFile()
        count("menu.home")

        val whileTyping = readsDuring {
            var typed = "x"
            for (letter in "node_modules") {
                edit(document, "\"$typed\"", "\"$typed$letter\"")
                typed += letter
                Assertions.assertEquals(1, count("menu.home"))
            }
        }
        val afterPause = readsDuring {
            settings.endTypingPause()
            Assertions.assertEquals(1, count("menu.home"))
        }

        Assertions.assertEquals(0, whileTyping, "nothing read while the typing goes on")
        Assertions.assertEquals(2, afterPause, "every file read once after the pause")
    }

    /** A keystroke that changes a setting the index reads with — a separator — reads the code again, all of it, after the pause. */
    @Test
    fun `a keystroke in the open config file that changes a separator reads the code again`() {
        menu()
        profile()
        val document = openConfigFile()
        count("menu.home")

        val whileTyping = readsDuring {
            edit(document, "\"keySeparator\": \".\"", "\"keySeparator\": \"/\"")
            count("menu.home")
        }
        val afterPause = readsDuring {
            settings.endTypingPause()
            count("menu.home")
        }

        Assertions.assertEquals(0, whileTyping, "nothing read before the pause")
        Assertions.assertTrue(afterPause >= 2, "every file read again, read $afterPause")
    }

    private fun openConfigFile(): Document {
        val file = addFileToProject(ProjectConfigFile.PATH, """{ "excludedDirectories": "x", "keySeparator": "." }""").virtualFile
        myFixture.openFileInEditor(file)
        return FileDocumentManager.getInstance().getDocument(file)!!
    }

    /** Replaces [from] by [to] in [document] without saving it, as typing does. */
    private fun edit(document: Document, from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val at = document.text.indexOf(from)
            check(at >= 0) { "$from not in ${document.text}" }
            document.replaceString(at, at + from.length, to)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        Assertions.assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }
}
