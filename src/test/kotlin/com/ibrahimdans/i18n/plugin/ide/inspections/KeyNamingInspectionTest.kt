package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.KeyNamingInspection
import com.ibrahimdans.i18n.plugin.ide.inspection.KeyNamingInspection.Convention
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A key not following the project's naming convention is reported, segment by segment, plural
 * suffix aside; *Rename to…* moves it everywhere through the key rename.
 */
class KeyNamingInspectionTest : PlatformBaseTest() {

    @AfterEach
    fun resetDialogs() {
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
    }

    private fun inspection(convention: Convention = Convention.CAMEL_CASE, pattern: String = "") =
        KeyNamingInspection().apply { this.convention = convention.name; customPattern = pattern }

    /** The naming warnings on `locales/en/common.<ext>` holding [content]. */
    private fun warnings(content: String, inspection: KeyNamingInspection = inspection(), ext: String = "json"): List<String> {
        myFixture.enableInspections(inspection)
        val file = myFixture.addFileToProject("locales/en/common.$ext", content)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Key '") }
    }

    @Test
    fun camelCaseReportsASnakeCaseKey() {
        assertEquals(
            listOf("Key 'user_name' does not follow the camelCase convention"),
            warnings("""{"userName": "Name", "user_name": "Name", "save": "Save"}""")
        )
    }

    @Test
    fun snakeCaseReportsACamelCaseKey() {
        assertEquals(
            listOf("Key 'userName' does not follow the snake_case convention"),
            warnings("""{"userName": "Name", "user_name": "Name"}""", inspection(Convention.SNAKE_CASE))
        )
    }

    @Test
    fun kebabCaseReportsTheOtherTwo() {
        val warnings = warnings("""{"userName": "a", "user_name": "b", "user-name": "c"}""", inspection(Convention.KEBAB_CASE))
        assertEquals(
            setOf("Key 'userName' does not follow the kebab-case convention", "Key 'user_name' does not follow the kebab-case convention"),
            warnings.toSet()
        )
    }

    @Test
    fun aCustomPatternMustMatchEachSegmentWhole() {
        assertEquals(
            listOf("Key 'Title' does not match the pattern '[a-z]+'"),
            warnings("""{"title": "a", "Title": "b"}""", inspection(Convention.CUSTOM, "[a-z]+"))
        )
    }

    @Test
    fun anInvalidOrBlankCustomPatternReportsNothing() {
        assertTrue(warnings("""{"Title": "b"}""", inspection(Convention.CUSTOM, "[a-z")).isEmpty())
        assertNull(KeyNamingInspection.ruleOf(Convention.CUSTOM, " "))
    }

    /** Each level of a nested file, and each part of a name holding the key separator, is a segment. */
    @Test
    fun eachSegmentIsCheckedOnItsOwn() {
        val warnings = warnings("""{"user_profile": {"firstName": "a"}, "home.page_title": "b", "home.pageTitle": "c"}""")
        assertEquals(
            setOf("Key 'user_profile' does not follow the camelCase convention", "Key 'home.page_title' does not follow the camelCase convention"),
            warnings.toSet()
        )
    }

    @Test
    fun aPluralSuffixIsNotReported() {
        assertTrue(warnings("""{"itemCount_one": "{{count}} item", "itemCount_other": "{{count}} items"}""").isEmpty())
    }

    @Test
    fun aPluralFormIsReportedOnItsBaseWithoutRename() {
        val warnings = warnings("""{"item_count_one": "a", "item_count_other": "b"}""")
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(myFixture.getAllQuickFixes().none { it.familyName == "Rename key…" })
    }

    @Test
    fun digitsOnlyPassEveryConvention() {
        assertTrue(warnings("""{"errors": {"404": "Not found"}}""", inspection(Convention.SNAKE_CASE)).isEmpty())
    }

    @Test
    fun yamlKeysAreChecked() {
        assertEquals(
            listOf("Key 'user_name' does not follow the camelCase convention"),
            warnings("userName: Name\nuser_name: Name\n", ext = "yaml")
        )
    }

    @Test
    fun offByDefault() {
        val tool = InspectionToolRegistrar.getInstance().createTools().first { it.shortName == "I18nKeyNaming" }
        assertFalse(tool.isEnabledByDefault)
    }

    @Test
    fun wordsConvertToEachConvention() {
        assertEquals(listOf("xml", "parser", "name"), KeyNamingInspection.words("XMLParser_name"))
        assertEquals("userName", KeyNamingInspection.ruleOf(Convention.CAMEL_CASE, "")!!.convert("user-name"))
        assertEquals("user_name", KeyNamingInspection.ruleOf(Convention.SNAKE_CASE, "")!!.convert("UserName"))
        assertEquals("cafe-menu", KeyNamingInspection.ruleOf(Convention.KEBAB_CASE, "")!!.convert("caféMenu"))
        assertNull(KeyNamingInspection.ruleOf(Convention.CUSTOM, "[a-z]+")!!.convert("Title"))
    }

    /** The fix opens the key rename, converted name filled in: every locale and call site follow. */
    @Test
    fun renameMovesTheKeyEverywhere() {
        myFixture.addFileToProject("locales/fr/common.json", """{"user_name": "Nom"}""")
        myFixture.addFileToProject("src/User.js", "export const u = (t) => t('common:user_name');")
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "userName"
            override fun show(message: String, validator: InputValidator?): String = "userName"
        })
        TestDialogManager.setTestDialog { Messages.YES }

        warnings("""{"user_name": "Name"}""")
        val fix = myFixture.getAllQuickFixes().single { it.familyName == "Rename key…" }
        assertEquals("Rename to 'userName'…", fix.text)
        myFixture.launchAction(fix)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("""{"userName": "Name"}""", myFixture.editor.document.text)
        assertEquals("""{"userName": "Nom"}""", text("locales/fr/common.json"))
        assertEquals("export const u = (t) => t('common:userName');", text("src/User.js"))
    }

    /** A level holding other keys moves with all of them. */
    @Test
    fun renameMovesALevel() {
        myFixture.addFileToProject("src/Profile.js", "export const p = (t) => t('common:user_profile.firstName');")
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = "userProfile"
            override fun show(message: String, validator: InputValidator?): String = "userProfile"
        })
        TestDialogManager.setTestDialog { Messages.YES }

        warnings("""{"user_profile": {"firstName": "First"}}""")
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.familyName == "Rename key…" })
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("""{"userProfile": {"firstName": "First"}}""", myFixture.editor.document.text)
        assertEquals("export const p = (t) => t('common:userProfile.firstName');", text("src/Profile.js"))
    }


    private fun text(path: String): String = ReadAction.compute<String, RuntimeException> {
        PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
    }
}
