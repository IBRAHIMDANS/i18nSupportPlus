package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.TestActionEvent
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class I18nizeActionTest : PlatformBaseTest() {

    private val config = Config(preferredLocalization = "json", defaultNs = "common")

    private fun compute(vararg selection: VirtualFile, indicator: com.intellij.openapi.progress.ProgressIndicator = EmptyProgressIndicator()) =
        ReadAction.compute<I18nizeAction.Preview, RuntimeException> { I18nizeAction.compute(project, selection.toList(), indicator) }

    private fun modelOf(preview: I18nizeAction.Preview) = I18nizePreviewModel(preview.plan, preview.existing, config)

    private fun write(plan: I18nizePlan): I18nizeApply.Outcome =
        ReadAction.compute<I18nizeApply, RuntimeException> { I18nizeApply.prepare(plan) }.write("Internationalize")

    private fun textOf(file: PsiFile): String = PsiDocumentManager.getInstance(project).getDocument(file)!!.text

    private fun I18nizePlan.keys(): List<String> = files.flatMap { file -> file.entries.map { it.key } }

    /** Three components in one folder, and what is not a JS/TS file left out. */
    private fun components(): List<PsiFile> = listOf(
        myFixture.addFileToProject("src/components/Header.tsx", "export function Header() { return <h1>Welcome</h1>; }"),
        myFixture.addFileToProject("src/components/Footer.jsx", "export function Footer() { return <p>Contact us</p>; }"),
        myFixture.addFileToProject("src/components/Menu.tsx", "export function Menu() { return <nav><p>Home</p><p>Settings</p></nav>; }"),
    ).also { myFixture.addFileToProject("src/components/readme.md", "Some text") }

    @Test
    fun aFolderOfThreeComponentsIsPlannedFileByFile() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val files = components()
        val texts = mutableListOf<String>()
        val indicator = object : ProgressIndicatorBase() {
            override fun setText2(text: String?) {
                super.setText2(text)
                text?.let(texts::add)
            }
        }

        val preview = compute(files.first().virtualFile.parent, indicator = indicator)

        Assertions.assertEquals(listOf("Footer.jsx", "Header.tsx", "Menu.tsx"), preview.plan.files.map { it.file.name })
        Assertions.assertEquals(listOf("footer.contact_us", "header.welcome", "menu.home", "menu.settings"), preview.plan.keys())
        Assertions.assertEquals(3, texts.distinct().size, texts.toString())
    }

    @Test
    fun anUncheckedTextIsNotExtracted() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val menu = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Home</p><p>Settings</p></nav>; }")
        val model = modelOf(compute(menu.virtualFile))

        model.rows.single { it.text == "Settings" }.selected = false
        write(model.accepted())

        Assertions.assertTrue("{t('menu.home')}" in textOf(menu), textOf(menu))
        Assertions.assertTrue("<p>Settings</p>" in textOf(menu), textOf(menu))
        Assertions.assertFalse("settings" in textOf(translations), textOf(translations))
    }

    @Test
    fun dataStartsUncheckedAndIsLeftAsIs() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val links = myFixture.addFileToProject("src/Links.tsx", """
            export function Links() {
                const site = 'https://example.com';
                return <p>Save</p>;
            }
        """.trimIndent())
        val model = modelOf(compute(links.virtualFile))

        Assertions.assertEquals(mapOf("https://example.com" to false, "Save" to true), model.rows.associate { it.text to it.selected })
        write(model.accepted())

        Assertions.assertTrue("'https://example.com'" in textOf(links), textOf(links))
        Assertions.assertTrue("{t('links.save')}" in textOf(links), textOf(links))
    }

    @Test
    fun aFileUncheckedWholeIsLeftOut() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", "{}")
        val files = components()
        val model = modelOf(compute(files.first().virtualFile.parent))

        model.files.single { it.first.name == "Menu.tsx" }.second.forEach { it.selected = false }

        Assertions.assertEquals(listOf("Footer.jsx", "Header.tsx"), model.accepted().files.map { it.file.name })
    }

    @Test
    fun anEditedKeyIsTheOneWritten() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val menu = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <p>Home</p>; }")
        val model = modelOf(compute(menu.virtualFile))

        val row = model.rows.single()
        row.key = "nav.start"
        Assertions.assertEquals(I18nizePreviewModel.Status.NEW, row.status())
        write(model.accepted())

        Assertions.assertTrue("{t('nav.start')}" in textOf(menu), textOf(menu))
        Assertions.assertTrue("\"start\": \"Home\"" in textOf(translations), textOf(translations))
    }

    @Test
    fun anEditedKeyIsCheckedAsItIsTyped() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}, "menu": {"open": "Open the menu"}}""")
        val menu = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Save</p><p>Open</p><p>Close</p></nav>; }")
        val model = modelOf(compute(menu.virtualFile))
        val (save, open, close) = model.rows

        Assertions.assertEquals(I18nizePreviewModel.Status.REUSED, save.status())
        Assertions.assertEquals(I18nizePreviewModel.Status.NEW, open.status())

        open.key = "menu.open"
        Assertions.assertEquals(I18nizePreviewModel.Status.TAKEN, open.status())
        Assertions.assertEquals("Open the menu", open.value())
        open.key = "menu..open"
        Assertions.assertEquals(I18nizePreviewModel.Status.INVALID, open.status())
        open.key = " "
        Assertions.assertEquals(I18nizePreviewModel.Status.EMPTY, open.status())
        Assertions.assertSame(open, model.firstError())

        open.key = "menu.button"
        close.key = "menu.button"
        Assertions.assertEquals(I18nizePreviewModel.Status.CLASH, close.status())
        close.selected = false
        Assertions.assertNull(model.firstError())
    }

    @Test
    fun aCancelWhileComputingWritesNothing() = myFixture.runWithConfig(config) {
        val translations = myFixture.addFileToProject("locales/en/common.json", "{}")
        val files = components()
        val before = files.map { textOf(it) }
        val indicator = object : ProgressIndicatorBase() {
            override fun setFraction(fraction: Double) {
                super.setFraction(fraction)
                // Cancelled once the first file is planned.
                if (fraction > 0) cancel()
            }
        }

        assertThrows<ProcessCanceledException> { compute(files.first().virtualFile.parent, indicator = indicator) }

        Assertions.assertEquals(before, files.map { textOf(it) })
        Assertions.assertEquals("{}", textOf(translations))
    }

    @Test
    fun theSummaryCountsKeysOnceAndFiles() = myFixture.runWithConfig(config) {
        myFixture.addFileToProject("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        val menu = myFixture.addFileToProject("src/Menu.tsx", "export function Menu() { return <nav><p>Save</p><p>Home</p><p>Home</p></nav>; }")
        val plan = compute(menu.virtualFile).plan

        val outcome = write(plan)

        Assertions.assertEquals(I18nizeAction.Summary(created = 1, reused = 1, files = 1, skipped = 0, createdFiles = 0), I18nizeAction.Summary.of(plan, emptyList(), outcome))
    }

    @Test
    fun theActionShowsOnJsAndTsFilesAndFoldersOnly() {
        val tsx = myFixture.addFileToProject("src/Menu.tsx", "").virtualFile
        val js = myFixture.addFileToProject("src/util.js", "").virtualFile
        val json = myFixture.addFileToProject("src/en.json", "{}").virtualFile
        val css = myFixture.addFileToProject("src/style.css", "").virtualFile

        Assertions.assertTrue(isVisibleOn(tsx))
        Assertions.assertTrue(isVisibleOn(js))
        Assertions.assertTrue(isVisibleOn(tsx.parent))
        Assertions.assertTrue(isVisibleOn(json, tsx))
        Assertions.assertFalse(isVisibleOn(json))
        Assertions.assertFalse(isVisibleOn(css))
        Assertions.assertFalse(isVisibleOn())
    }

    private fun isVisibleOn(vararg files: VirtualFile): Boolean {
        val action = ActionManager.getInstance().getAction("com.ibrahimdans.i18n.Internationalize")
        Assertions.assertTrue(action is I18nizeAction)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }
}
