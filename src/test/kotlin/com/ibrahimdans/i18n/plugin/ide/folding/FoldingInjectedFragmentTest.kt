package com.ibrahimdans.i18n.plugin.ide.folding

import com.ibrahimdans.i18n.extensions.lang.js.AngularFoldingBuilder
import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runVue
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EditorTestUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The platform folds an injected fragment on a pass of its own, the fragment as root: the regions
 * must then belong to the fragment, in the fragment's offsets. They were placed on the host whatever
 * the root — "Folding descriptor … must belong to the file …" logged per key, the IDE's error
 * balloon — and nothing of that pass folded. The builders are called here as that pass calls them.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class FoldingInjectedFragmentTest : PlatformBaseTest() {

    private val config = Config(
        defaultNs = "translation",
        foldingPreferredLanguage = "en",
        foldingMaxLength = 20,
        foldingEnabled = true,
    )

    private fun withTranslations(block: () -> Unit) = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/translation.json", """{"menu":{"home":"Home","help":"Help"}}""")
        block()
    }

    private fun openInlineAngularComponent() {
        addFileToProject("package.json", """{"dependencies":{"@angular/core":"17.0.0","@ngx-translate/core":"15.0.0"}}""")
        addFileToProject("node_modules/@angular/core/package.json", """{"name":"@angular/core","version":"17.0.0"}""")
        myFixture.configureByText(
            "inline.component.ts",
            "import { Component } from '@angular/core';\n" +
                "@Component({ selector: 'app-inline', template: `<p>{{ 'menu.home' | translate }}</p>` })\n" +
                "export class InlineComponent {}\n"
        )
    }

    /** The fragments injected into the hosts of [file] whose language derives from [languageId]. */
    private fun fragments(file: PsiFile, languageId: String): List<PsiFile> {
        val manager = InjectedLanguageManager.getInstance(project)
        return PsiTreeUtil.findChildrenOfType(file, PsiLanguageInjectionHost::class.java)
            .flatMap { host -> manager.getInjectedPsiFiles(host).orEmpty().map { it.first.containingFile } }
            .distinct()
            .filter { fragment -> generateSequence(fragment.language) { it.baseLanguage }.any { it.id == languageId } }
    }

    /** What [builder] returns for [fragment] as root, as the platform's pass of the fragment asks it. */
    private fun fragmentPass(builder: FoldingBuilderEx, fragment: PsiFile): List<FoldingDescriptor> =
        ReadAction.compute<List<FoldingDescriptor>, RuntimeException> {
            val document = PsiDocumentManager.getInstance(project).getDocument(fragment)!!
            builder.buildFoldRegions(fragment, document, false).toList()
        }

    /** Each region of a fragment's pass must belong to the fragment and lie in its text. */
    private fun assertOnTheFragment(fragment: PsiFile, descriptors: List<FoldingDescriptor>): List<String> {
        Assertions.assertFalse(descriptors.isEmpty(), "the fragment's own pass folds its keys")
        descriptors.forEach {
            Assertions.assertSame(fragment, it.element.psi?.containingFile, "the region belongs to the fragment, not to its host")
            Assertions.assertTrue(it.range.endOffset <= fragment.textLength, "the range is in the fragment's offsets: ${it.range}")
        }
        return descriptors.map { fragment.text.substring(it.range.startOffset, it.range.endOffset) }
    }

    /** The placeholders of the regions the editor holds after the platform's own passes. */
    private fun placeholders(): List<String> {
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.map { it.placeholderText }
    }

    @Test
    fun `the pass of an Angular component's inline template puts the regions on the fragment`() = withTranslations {
        openInlineAngularComponent()
        val fragment = fragments(myFixture.file, "Angular2Html").single()

        val folded = assertOnTheFragment(fragment, fragmentPass(AngularFoldingBuilder(), fragment))

        Assertions.assertEquals(listOf("'menu.home' | translate"), folded)
    }

    /** Folded from the component and from the fragment: the editor keeps one region, and no error. */
    @Test
    fun `an inline template's key folds once through the platform's passes`() = withTranslations {
        openInlineAngularComponent()

        val placeholders = placeholders()

        Assertions.assertEquals(1, placeholders.count { it == "Home" }, "$placeholders")
    }

    @Test
    fun `the pass of a Vue template's expression puts the regions on the fragment`() = runVue {
        withTranslations {
            myFixture.configureByText("App.vue", "<template>\n  <p>{{ \$t('menu.home') }}</p>\n</template>\n")
            val fragments = fragments(myFixture.file, "JavaScript")
            Assertions.assertFalse(fragments.isEmpty(), "the interpolation is a JavaScript fragment injected into the template")

            val folded = fragments.flatMap { fragment -> assertOnTheFragment(fragment, fragmentPass(JsFoldingBuilder(), fragment)) }

            Assertions.assertEquals(listOf("\$t('menu.home')"), folded)
            val placeholders = placeholders()
            Assertions.assertEquals(1, placeholders.count { it == "Home" }, "$placeholders")
        }
    }
}
