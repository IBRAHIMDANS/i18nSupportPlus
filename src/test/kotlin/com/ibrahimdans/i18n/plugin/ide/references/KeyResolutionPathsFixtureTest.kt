package com.ibrahimdans.i18n.plugin.ide.references

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.I18nGutterIconProvider
import com.ibrahimdans.i18n.plugin.ide.hint.HintProvider
import com.ibrahimdans.i18n.plugin.ide.inlay.I18nInlayHintsProvider
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The cases of [KeyResolutionFixtureTest], through every other path that reads a key: the hint
 * (Ctrl+hover documentation), the inlay hint after the key, folding, the gutter icon, and completion.
 *
 * They all go through the same resolution but each has its own entry point — which is where
 * `<Trans i18nKey>` diverged, resolved by none while `t('…')` was resolved by all. Each case is run
 * through each path: the key's value must show, and in a monorepo only its own module's value.
 * A gap is a `@Disabled` test asserting the behaviour we want, with the reason.
 *
 * Same fixture project as [KeyResolutionFixtureTest] (`src/test/resources/keyResolution`).
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class KeyResolutionPathsFixtureTest : PlatformBaseTest() {

    override fun getTestDataPath(): String = "src/test/resources/keyResolution"

    /** A key written as [written] (quotes included) in [path], whose value must show as [value] — and never [foreign]. */
    data class Case(val project: String, val path: String, val written: String, val value: String, val foreign: String? = null) {
        override fun toString() = "$path $written"
    }

    private companion object {
        const val CARET = "<caret>"

        @JvmStatic
        fun cases() = listOf(
            Case("single", "src/Explicit.tsx", "'common:title'", "Title"),
            Case("single", "src/Default.tsx", "'defaultOnly'", "Default only"),
            Case("single", "src/Nested.tsx", "'common:user.name'", "User name"),
            Case("single", "src/Hook.tsx", "'dashboard.title'", "Dashboard"),
            Case("single", "src/Prefix.tsx", "'name'", "Profile name"),
            Case("single", "src/NsOption.tsx", "'label'", "Other label"),
            Case("single", "src/Trans.tsx", "\"common:title\"", "Title"),
            Case("monorepo", "apps/web/src/Title.tsx", "'common:title'", "Web title", foreign = "Admin title"),
            Case("monorepo", "apps/admin/src/Title.tsx", "'common:title'", "Admin title", foreign = "Web title"),
            Case("monorepo", "apps/docs/src/Doc.tsx", "'guide.intro'", "Guide intro"),
        )

        val TRANS = cases().single { it.path == "src/Trans.tsx" }

        /** Every case but `<Trans i18nKey>`, which the hint and folding do not read yet — see their gap tests. */
        @JvmStatic
        fun callCases() = cases() - TRANS
    }

    private val display = Config(defaultNs = "common", pluralSeparator = "_", foldingEnabled = true, foldingPreferredLanguage = "en", foldingMaxLength = 40)

    // As in KeyResolutionFixtureTest: the fixture's files live under the in-memory root `/src`.
    private val monorepo = display.copy(
        modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "src/apps/web"),
            ModuleConfig(name = "admin", rootDirectory = "src/apps/admin"),
            ModuleConfig(
                name = "docs", rootDirectory = "src/apps/docs",
                pathTemplate = "locales/{lang}/{ns}.json", keyTemplate = "{ns}.{key}"
            ),
        )
    )

    private fun open(case: Case, block: () -> Unit) {
        myFixture.copyDirectoryToProject(case.project, "")
        myFixture.runWithConfig(if (case.project == "monorepo") monorepo else display) {
            myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(case.path))
            block()
        }
    }

    /** The offset of a character inside the key, past its opening quote. */
    private fun keyOffset(case: Case): Int = myFixture.file.text.indexOf(case.written) + 1

    private fun assertShows(case: Case, path: String, shown: List<String>) {
        Assertions.assertTrue(shown.any { case.value in it }, "$path: ${case} should show \"${case.value}\", showed $shown")
        case.foreign?.let { foreign -> Assertions.assertFalse(shown.any { foreign in it }, "$path: ${case} showed another module's \"$foreign\": $shown") }
    }

    // ── Hint ──────────────────────────────────────────────────────────────────

    private fun hintOf(case: Case): List<String> = listOfNotNull(ReadAction.compute<String?, RuntimeException> {
        HintProvider().generateDoc(null, myFixture.file.findElementAt(keyOffset(case)))
    })

    @ParameterizedTest
    @MethodSource("callCases")
    fun `the hint shows the value`(case: Case) = open(case) {
        assertShows(case, "hint", hintOf(case))
    }

    /**
     * Gap: `HintProvider.generateDoc` is handed the leaf token under the caret, and
     * `XmlAttributeKeyExtractor` only accepts the `XmlAttributeValue` above it — a JS string
     * literal's leaf is accepted as it is, an attribute value's is not.
     */
    @Disabled("no hint on <Trans i18nKey>")
    @Test
    fun `the hint shows the value of a Trans i18nKey`() = open(TRANS) {
        assertShows(TRANS, "hint", hintOf(TRANS))
    }

    // ── Inlay ─────────────────────────────────────────────────────────────────

    private class CapturingBuilder : PresentationTreeBuilder {
        val text = StringBuilder()
        override fun text(text: String, actionData: InlayActionData?) {
            this.text.append(text)
        }

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder(this)
        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) = builder(this)
        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit
        ) = Unit
    }

    private class CapturingSink : InlayTreeSink {
        val hints = mutableListOf<String>()
        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            hints += CapturingBuilder().also(builder).text.toString()
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `the inlay hint shows the value`(case: Case) = open(case) {
        val collector = I18nInlayHintsProvider().createCollector(myFixture.file, myFixture.editor) as SharedBypassCollector
        val sink = CapturingSink()
        read {
            PsiTreeUtil.processElements(myFixture.file) { element ->
                collector.collectFromElement(element, sink)
                true
            }
        }
        assertShows(case, "inlay", sink.hints)
    }

    // ── Folding ───────────────────────────────────────────────────────────────

    private fun placeholders(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        JsFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false).map { it.placeholderText.orEmpty() }
    }

    @ParameterizedTest
    @MethodSource("callCases")
    fun `folding shows the value`(case: Case) = open(case) {
        assertShows(case, "folding", placeholders())
    }

    /** Gap: `JsFoldingBuilder` is built on `JsLang`, which lacks `JsxLang`'s `i18nKey` extractor (#443). */
    @Disabled("<Trans i18nKey> is not folded")
    @Test
    fun `folding shows the value of a Trans i18nKey`() = open(TRANS) {
        assertShows(TRANS, "folding", placeholders())
    }

    // ── Gutter ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("cases")
    fun `the gutter marks the key`(case: Case) = open(case) {
        val markers = mutableListOf<LineMarkerInfo<*>>()
        ReadAction.run<RuntimeException> {
            val provider = I18nGutterIconProvider()
            myFixture.file.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    provider.getLineMarkerInfo(element)?.let { markers += it }
                    super.visitElement(element)
                }
            })
        }
        Assertions.assertEquals(1, markers.size, "gutter: $case should carry one marker, carried ${markers.size}")
    }

    // ── Completion ────────────────────────────────────────────────────────────

    /**
     * What completion offers in an empty key of [code], written to [path] — the lookup, or the one key
     * the platform inserted by itself when it was the only one.
     */
    private fun offered(path: String, code: String): List<String> {
        val caret = code.indexOf(CARET)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, code.replace(CARET, "")).virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        val lookup = myFixture.completeBasic()
        if (lookup != null) return myFixture.lookupElementStrings.orEmpty()
        val text = myFixture.editor.document.text
        return listOf(text.substring(caret, text.indexOf('\'', caret)))
    }

    private fun component(hook: String) =
        "import { useTranslation } from 'react-i18next';\n" +
            "export const A = () => { const { t } = $hook; return <p>{t('$CARET')}</p>; };\n"

    @Test
    fun `completion offers the keys of the hook's namespace`() {
        myFixture.copyDirectoryToProject("single", "")
        myFixture.runWithConfig(display) {
            val offered = offered("src/CompleteHook.tsx", component("useTranslation('admin')"))
            // One level at a time: `dashboard`, then `title` once it is typed.
            Assertions.assertTrue("dashboard" in offered, "offered $offered")
            Assertions.assertFalse(offered.any { it.endsWith("defaultOnly") }, "another namespace's keys: $offered")
        }
    }

    @Test
    fun `completion offers the keys under a hook's key prefix`() {
        myFixture.copyDirectoryToProject("single", "")
        myFixture.runWithConfig(display) {
            val offered = offered("src/CompletePrefix.tsx", component("useTranslation('common', { keyPrefix: 'profile' })"))
            Assertions.assertTrue("name" in offered, "the prefix's keys, without the prefix: $offered")
            Assertions.assertFalse(offered.any { it.endsWith("title") }, "keys outside the prefix: $offered")
        }
    }

    @Test
    fun `completion in a module offers its own keys only`() {
        myFixture.copyDirectoryToProject("monorepo", "")
        myFixture.runWithConfig(monorepo) {
            val offered = offered("apps/web/src/Complete.tsx", component("useTranslation('common')"))
            Assertions.assertTrue(offered.any { it.endsWith("webOnly") }, "the module's own keys: $offered")
            Assertions.assertFalse(offered.any { it.endsWith("adminOnly") }, "another module's keys: $offered")
        }
    }
}
