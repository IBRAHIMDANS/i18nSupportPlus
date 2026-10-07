package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.waitForAsyncWork
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeleteI18nKeyActionTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    /** The element of [path] at the first occurrence of [marker], once the file is open in the editor. */
    private fun elementIn(path: String, marker: String): PsiElement {
        val file = myFixture.findFileInTempDir(path)
        myFixture.configureFromExistingVirtualFile(file)
        val offset = myFixture.editor.document.text.indexOf(marker)
        myFixture.editor.caretModel.moveToOffset(offset)
        return myFixture.file.findElementAt(offset)!!
    }

    private fun planAt(path: String, marker: String): DeleteI18nKeyAction.Plan? =
        ReadAction.compute<DeleteI18nKeyAction.Plan?, RuntimeException> { DeleteI18nKeyAction.planOf(elementIn(path, marker)) }

    private fun valueAt(path: String, vararg key: String): String? = ReadAction.compute<String?, RuntimeException> {
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path)) as JsonFile
        var node = file.topLevelValue as JsonObject
        for (segment in key.dropLast(1)) node = node.findProperty(segment)?.value as? JsonObject ?: return@compute null
        (node.findProperty(key.last())?.value as? JsonStringLiteral)?.value
    }

    @Test
    fun anUnusedKeyOfATranslationFileIsPlannedWithoutUsages() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        val plan = planAt("locales/en/common.json", "home")
        assertNotNull(plan)
        assertEquals(listOf("common:menu.home"), plan!!.keys)
        assertTrue(plan.usages.isEmpty(), "${plan.usages}")
    }

    @Test
    fun aKeyUsedInTheCodeListsWhere() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        val plan = planAt("locales/en/common.json", "home")!!
        assertEquals(listOf("App.js:1"), plan.usages)
    }

    @Test
    fun theKeyIsFoundFromTheCode() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/App.js", "export const label = (t) => t('common:menu.home');")
        val plan = planAt("src/App.js", "menu.home")!!
        assertEquals(listOf("common:menu.home"), plan.keys)
    }

    @Test
    fun aPluralIsDeletedWithAllItsForms() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"item_one": "{{count}} item", "item_other": "{{count}} items", "other": "x"}""")
        val plan = planAt("locales/en/common.json", "item_one")!!
        assertEquals(setOf("common:item_one", "common:item_other"), plan.keys.toSet())
    }

    /** Deleting `menu` would take every key below it. */
    @Test
    fun anObjectIsNotOffered() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home"}}""")
        val element = elementIn("locales/en/common.json", "menu")
        assertNull(ReadAction.compute<List<String>?, RuntimeException> { DeleteI18nKeyAction.translationPathAt(element) })
    }

    @Test
    fun theKeyIsDeletedFromEveryLocaleOnceConfirmed() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"menu": {"home": "Home", "about": "About"}}""")
        addFileToProject("locales/fr/common.json", """{"menu": {"home": "Accueil", "about": "À propos"}}""")
        elementIn("locales/fr/common.json", "home")
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            myFixture.testAction(DeleteI18nKeyAction())
            waitForAsyncWork()
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        assertNull(valueAt("locales/en/common.json", "menu", "home"))
        assertNull(valueAt("locales/fr/common.json", "menu", "home"))
        assertEquals("À propos", valueAt("locales/fr/common.json", "menu", "about"))
    }
}
