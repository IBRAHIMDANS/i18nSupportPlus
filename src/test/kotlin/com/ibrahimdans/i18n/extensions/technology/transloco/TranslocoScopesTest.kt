package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.components.service
import com.intellij.psi.PsiPolyVariantReference
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Transloco's scopes: `admin.title` is the key `title` of `i18n/admin/en.json`. */
class TranslocoScopesTest : PlatformBaseTest() {

    private val unresolved = PluginBundle.getMessage("annotator.unresolved.key")

    private fun translations() {
        addFileToProject("src/assets/i18n/en.json", """{"menu": {"home": "Home"}}""")
        addFileToProject("src/assets/i18n/admin/en.json", """{"title": "Admin", "users": {"list": "Users"}}""")
    }

    private fun translocoProject() {
        addFileToProject("package.json", """{"dependencies": {"@angular/core": "17.0.0", "@jsverse/transloco": "7.0.0"}}""")
        translations()
    }

    /** The keys reported unresolved in [code], as written. */
    private fun unresolvedIn(code: String): List<String> {
        myFixture.configureFromExistingVirtualFile(addFileToProject("src/app/admin.component.ts", code).virtualFile)
        return myFixture.doHighlighting().filter { it.description == unresolved }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun aScopedKeyResolvesInItsScopeFile() {
        translocoProject()
        val reported = unresolvedIn(
            """
            const a = this.translocoService.translate('admin.title');
            const b = this.translocoService.translate('admin.users.list');
            const c = this.translocoService.translate('menu.home');
            const d = this.translocoService.translate('admin.missing');
            const e = this.translocoService.translate('menu.missing');
            """.trimIndent()
        )
        Assertions.assertEquals(listOf("missing", "missing"), reported.map { it.substringAfterLast('.') }, "reported: $reported")
        Assertions.assertEquals(2, reported.size, "reported: $reported")
    }

    @Test
    fun aScopeKeyLeadsBackToItsCallSite() {
        translocoProject()
        addFileToProject("src/app/admin.component.ts", "const a = this.translocoService.translate('admin.users.list');")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("src/assets/i18n/admin/en.json"))
        val offset = myFixture.editor.document.text.indexOf("\"list\"") + 2
        val reference = myFixture.file.findElementAt(offset)!!.parent.references
            .filterIsInstance<PsiPolyVariantReference>().firstOrNull()
        Assertions.assertNotNull(reference, "the scope file's key must reference its call sites")
        Assertions.assertEquals(1, reference!!.multiResolve(false).size)
    }

    @Test
    fun theScopeFileIsListedOnceUnderItsScope() {
        translocoProject()
        val sources = project.service<LocalizationSourceService>().findAllSources(project)
            .filter { it.displayPath.endsWith("admin/en.json") }
        Assertions.assertEquals(listOf("admin"), sources.map { it.namespace })
    }

    /** Without Transloco, `locales/admin/en.json` is no scope: `admin.title` is still a global key. */
    @Test
    fun withoutTranslocoTheFirstSegmentIsNoScope() {
        translations()
        val reported = unresolvedIn("const a = i18n.t('admin.title');")
        Assertions.assertEquals(listOf("title"), reported.map { it.substringAfterLast('.') }, "reported: $reported")
    }
}
