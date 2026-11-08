package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.GenerateI18nTypesAction.Reading
import com.ibrahimdans.i18n.plugin.ide.actions.GenerateI18nTypesAction.Target
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * What *Generate i18next Types* reads before writing anything: the declaration of the reference
 * locale's keys, or the reason there is none — never a declaration without keys, which would turn
 * every `t('…')` of the module into a type error.
 */
class GenerateI18nTypesActionTest : PlatformBaseTest() {

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    private fun reading(target: Target = Target(null, "/project"), config: Config = Config()): Reading {
        var result: Reading? = null
        myFixture.runWithConfig(config) {
            read { result = GenerateI18nTypesAction.readReference(project, target) }
        }
        return result!!
    }

    @Test
    fun `the keys of the reference locale are declared`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("locales/fr/common.json", """{"actions": {"save": "Enregistrer", "extra": "x"}}""")

        val declaration = assertInstanceOf(Reading.Declaration::class.java, reading())
        assertTrue("save: string;" in declaration.text, declaration.text)
        assertTrue("extra" !in declaration.text, declaration.text)
        assertEquals(emptyList<String>(), declaration.unread)
    }

    @Test
    fun `no file of the reference locale, nothing to declare`() {
        addFileToProject("locales/fr/common.json", """{"actions": {"save": "Enregistrer"}}""")
        addFileToProject("locales/de/common.json", """{"actions": {"save": "Speichern"}}""")

        assertEquals(Reading.NoReference("en", listOf("de", "fr")), reading())
    }

    @Test
    fun `a misspelled reference locale of the module, nothing to declare`() {
        addFileToProject("apps/web/locales/en/common.json", """{"actions": {"save": "Save"}}""")
        val module = ModuleConfig(name = "web", rootDirectory = "apps/web", referenceLocale = "eng")

        assertEquals(
            Reading.NoReference("eng", listOf("en")),
            reading(Target(module, "/project/apps/web"), Config(modules = listOf(module)))
        )
    }

    @Test
    fun `a namespace in a format the generator does not read is named, the others declared`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        addFileToProject("locales/en/errors.po", "msgid \"not_found\"\nmsgstr \"Not found\"\n")

        val declaration = assertInstanceOf(Reading.Declaration::class.java, reading())
        assertTrue("save: string;" in declaration.text, declaration.text)
        assertEquals(listOf("errors"), declaration.unread)
    }

    @Test
    fun `only files in a format the generator does not read, nothing to declare`() {
        addFileToProject("locales/en/errors.po", "msgid \"not_found\"\nmsgstr \"Not found\"\n")

        assertEquals(Reading.NoReference("en", listOf("en")), reading())
    }

    @Test
    fun `a language alone finds its regional variant`() {
        addFileToProject("locales/en-US/common.json", """{"actions": {"save": "Save"}}""")

        val declaration = assertInstanceOf(Reading.Declaration::class.java, reading())
        assertTrue("save: string;" in declaration.text, declaration.text)
    }

    @Test
    fun `a generation cancelled while it reads writes nothing and asks nothing`() {
        addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        val target = Target(null, project.basePath!!)
        val started = CountDownLatch(1)
        // A reading that ends only once cancelled, checking for it as the real one does between files.
        fun endless(
            @Suppress("UNUSED_PARAMETER") project: Project,
            @Suppress("UNUSED_PARAMETER") targets: List<Target>,
            @Suppress("UNUSED_PARAMETER") indicator: ProgressIndicator,
        ): Map<Target, GenerateI18nTypesAction.Plan> {
            started.countDown()
            while (true) {
                ProgressManager.checkCanceled()
                Thread.onSpinWait()
            }
        }
        TestDialogManager.setTestDialog { message -> throw AssertionError("Unexpected modal: $message") }

        val job = GenerateI18nTypesAction.run(project, listOf(target), ::endless)
        PlatformTestUtil.waitWithEventsDispatching("the reading never started", { started.count == 0L }, 30)
        job.cancel()
        PlatformTestUtil.waitWithEventsDispatching("the cancelled reading never ended", { job.isCompleted }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(job.isCancelled, "the job ends cancelled")
        assertNull(LocalFileSystem.getInstance().findFileByPath(GenerateI18nTypesAction.targetFileOf(target)), "no declaration written")
        read {
            assertTrue(
                FilenameIndex.getVirtualFilesByName(GenerateI18nTypesAction.FILE_NAME, GlobalSearchScope.allScope(project)).isEmpty(),
                "no declaration in the project"
            )
        }
    }
}
