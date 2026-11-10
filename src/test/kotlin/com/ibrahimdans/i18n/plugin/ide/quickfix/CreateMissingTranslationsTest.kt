package com.ibrahimdans.i18n.plugin.ide.quickfix

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.EditTranslationAtCaretAction
import com.ibrahimdans.i18n.plugin.ide.dialog.Mode
import com.ibrahimdans.i18n.plugin.ide.dialog.TranslationDialog
import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.generator.code.TsxCodeGenerator
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * *Create missing translations*, on a key some checked locales hold and others lack.
 *
 * The fix used to write **the key itself** as the value into every locale lacking it
 * (`"base": "test:root.sub.base"`), a string that looked translated, one undo step per file.
 * It now opens the translation dialog on the key: the locales holding it show their value,
 * the others are left to fill, and nothing is written on Cancel.
 *
 * The dialog is built for real and driven headlessly — a `DialogWrapper` cannot be shown in a
 * test container — through [EditTranslationAtCaretAction.opener], the door the fix opens it by.
 */
class CreateMissingTranslationsTest : PlatformBaseTest() {

    private val config = Config(partialTranslationInspectionEnabled = true)
    private val hint get() = PluginBundle.getMessage("quickfix.create.missing.keys")

    /** The modes and values the dialog opened on, by locale, in order. */
    private val opened = mutableListOf<Pair<Mode, Map<String, String?>>>()

    /** Opens the real dialog, records what it shows, types [values] and confirms; cancels when null. */
    private fun answerDialogWith(values: Map<String, String>?, locales: List<String> = listOf("en", "ru", "de")) {
        EditTranslationAtCaretAction.opener = { project, target ->
            val dialog = TranslationDialog(project, target.fullKey, target.mode, target.caller)
            try {
                opened += target.mode to locales.associateWith { dialog.valueOf(it) }
                values?.forEach { (locale, value) -> dialog.setValue(locale, value) }
                if (values != null) dialog.confirm()
                values != null
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }

    @AfterEach
    fun restoreDialog() {
        EditTranslationAtCaretAction.opener = { project, target ->
            TranslationDialog(project, target.fullKey, target.mode, target.caller).showAndGet()
        }
    }

    private fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)!!.text

    /** `en` holds `root.sub.base`, `ru` lacks it, the caret is on the key. */
    private fun partialKey(): Pair<VirtualFile, VirtualFile> {
        val en = myFixture.addFileToProject("en/test.json", """{"root": {"sub": {"base": "Partially defined translation"}}}""")
        val ru = myFixture.addFileToProject("ru/test.json", """{"root": {"another": "value"}}""")
        myFixture.configureByText("sample.tsx", TsxCodeGenerator().generate("'test:root.sub.ba<caret>se'"))
        return en.virtualFile to ru.virtualFile
    }

    @Test
    fun testUnavailableFix() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("en/test.json", """{"root": {"sub": {"base": "Partially defined translation"}}}""")
        myFixture.configureByText("sample.tsx", TsxCodeGenerator().generate("'test:root.sub.ba<caret>se'"))
        Assertions.assertNull(myFixture.getAllQuickFixes().find { it.text == hint })
    }

    @Test
    fun theFixOpensTheDialogOnTheKeyWithTheValuesItHas() = myFixture.runWithConfig(config) {
        partialKey()
        answerDialogWith(null, locales = listOf("en", "ru"))
        myFixture.launchActionAndWait(myFixture.getAllQuickFixes().single { it.text == hint })

        Assertions.assertEquals(1, opened.size, "the dialog must open once")
        Assertions.assertEquals(Mode.EDIT, opened.single().first)
        Assertions.assertEquals("Partially defined translation", opened.single().second["en"])
        Assertions.assertEquals("", opened.single().second["ru"], "the locale lacking the key is left to fill")
    }

    @Test
    fun cancelWritesNothingAndTheKeyIsNeverTheValue() = myFixture.runWithConfig(config) {
        val (en, ru) = partialKey()
        answerDialogWith(null, locales = listOf("en", "ru"))
        myFixture.launchActionAndWait(myFixture.getAllQuickFixes().single { it.text == hint })

        Assertions.assertEquals("""{"root": {"another": "value"}}""", text(ru))
        Assertions.assertEquals("""{"root": {"sub": {"base": "Partially defined translation"}}}""", text(en))
        Assertions.assertTrue("test:root.sub.base" !in text(ru), text(ru))
    }

    @Test
    fun theValueTypedIsWrittenWhereTheKeyIsMissing() = myFixture.runWithConfig(config) {
        val (en, ru) = partialKey()
        answerDialogWith(mapOf("ru" to "Частично"), locales = listOf("en", "ru"))
        myFixture.launchActionAndWait(myFixture.getAllQuickFixes().single { it.text == hint })

        Assertions.assertTrue("\"base\": \"Частично\"" in text(ru), text(ru))
        Assertions.assertTrue("test:root.sub.base" !in text(ru), text(ru))
        Assertions.assertEquals("""{"root": {"sub": {"base": "Partially defined translation"}}}""", text(en))
    }

    /** Every locale written goes in one command: one Ctrl+Z undoes the fix as a whole. */
    @Test
    fun everyLocaleWrittenIsOneCommand() = myFixture.runWithConfig(config) {
        partialKey()
        val de = myFixture.addFileToProject("de/test.json", """{"root": {"another": "Wert"}}""").virtualFile
        val commands = mutableListOf<String?>()
        project.messageBus.connect(testRootDisposable).subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandFinished(event: CommandEvent) {
                commands += event.commandName
            }
        })
        answerDialogWith(mapOf("ru" to "Частично", "de" to "Teilweise"))
        myFixture.launchActionAndWait(myFixture.getAllQuickFixes().single { it.text == hint })

        Assertions.assertTrue("\"base\": \"Teilweise\"" in text(de), text(de))
        val writes = commands.filter { it == PluginBundle.message("dialog.translation.command.update") }
        Assertions.assertEquals(1, writes.size, "one command for both locales: $commands")
    }

    /**
     * A translation file that cannot be made writable — locked by a VCS, or its prompt refused —
     * stops the fix before anything is written.
     *
     * The test container clears a read-only flag whenever it is asked to: a [WritingAccessProvider]
     * refusing the file stands for the lock, as a VCS plugin's does.
     */
    @Test
    fun aLockedFileLeavesNoPartialWrite() = myFixture.runWithConfig(config) {
        val (en, ru) = partialKey()
        WritingAccessProvider.EP.getPoint(project).registerExtension(object : WritingAccessProvider() {
            override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filter { it == ru }
            override fun isPotentiallyWritable(file: VirtualFile): Boolean = file != ru
        }, testRootDisposable)
        answerDialogWith(mapOf("ru" to "Частично", "en" to "Partly"), locales = listOf("en", "ru"))
        myFixture.launchActionAndWait(myFixture.getAllQuickFixes().single { it.text == hint })

        Assertions.assertTrue(opened.isEmpty(), "the dialog must not open over a locked file")
        Assertions.assertEquals("""{"root": {"sub": {"base": "Partially defined translation"}}}""", text(en))
        Assertions.assertEquals("""{"root": {"another": "value"}}""", text(ru))
    }

    @Test
    fun theFixIsOfferedOnAPartialKey() = myFixture.runWithConfig(config) {
        partialKey()
        Assertions.assertNotNull(myFixture.getAllQuickFixes().find { it.text == hint })
    }
}
