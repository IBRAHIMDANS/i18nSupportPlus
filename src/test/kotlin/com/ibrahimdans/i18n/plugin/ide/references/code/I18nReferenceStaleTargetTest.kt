package com.ibrahimdans.i18n.plugin.ide.references.code

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** A reference built before its translation was edited away resolves to nothing instead of throwing. */
class I18nReferenceStaleTargetTest : PlatformBaseTest() {

    @Test
    fun `a key whose object was removed from the file since the reference was built resolves to nothing`() {
        val translation = addFileToProject("assets/test.json", """{"ref": {"key": "Value"}}""") as JsonFile
        myFixture.configureByText("Stale.js", "export const a = (t) => t('test:ref');")
        val reference = ApplicationManager.getApplication().runReadAction(Computable {
            PsiTreeUtil.findChildrenOfType(myFixture.file, JSLiteralExpression::class.java)
                .single { it.text == "'test:ref'" }
                .references.filterIsInstance<I18nReference>().single()
        })

        WriteCommandAction.runWriteCommandAction(project) {
            (translation.topLevelValue as JsonObject).findProperty("ref")!!.value!!.delete()
        }

        val results = ApplicationManager.getApplication().runReadAction(Computable { reference.multiResolve(false) })
        Assertions.assertEquals(0, results.size)
    }
}
