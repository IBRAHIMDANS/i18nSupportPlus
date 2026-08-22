package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.generator.code.JsCodeGenerator
import com.ibrahimdans.i18n.plugin.utils.generator.translation.JsonTranslationGenerator
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * `I18nInlayHintsProvider` had no test at all, while folding and hints — which share its
 * resolution path and its `unQuote() -> renderIcu() -> ellipsis()` display pipeline — had
 * eleven suites between them. Anything changed in that pipeline was verified on two of the
 * three display points and read over on the third.
 *
 * The declarative inlay API offers no fixture helper here, so the collector is driven
 * directly and its presentations captured.
 */
class I18nInlayHintsProviderTest : PlatformBaseTest() {

    /** Records the text a provider pushes, ignoring the parts of the tree we do not use. */
    private class CapturingBuilder : PresentationTreeBuilder {
        val text = StringBuilder()
        override fun text(text: String, actionData: InlayActionData?) {
            this.text.append(text)
        }

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder(this)
        override fun clickHandlerScope(
            actionData: InlayActionData,
            builder: PresentationTreeBuilder.() -> Unit
        ) = builder(this)

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit
        ) = Unit
    }

    private class CapturingSink : InlayTreeSink {
        val hints = mutableListOf<Pair<Int, String>>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            val captured = CapturingBuilder().also(builder)
            hints += ((position as? InlineInlayPosition)?.offset ?: -1) to captured.text.toString()
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    private fun collectHints(): List<String> {
        val collector = I18nInlayHintsProvider()
            .createCollector(myFixture.file, myFixture.editor) as SharedBypassCollector
        val sink = CapturingSink()
        read {
            PsiTreeUtil.processElements(myFixture.file) { element ->
                collector.collectFromElement(element, sink)
                true
            }
        }
        return sink.hints.map { it.second }
    }

    private fun configure(ns: String, value: String, key: String = "root.first.second") {
        val tg = JsonTranslationGenerator()
        addFileToProject("en/$ns.${tg.ext()}", tg.generateContent("root", "first", "second", value))
        myFixture.configureByText("content_$ns.js", JsCodeGenerator().generate("\"$ns:$key\"", 0))
    }

    @Test
    fun testResolvedKeyProducesHintWithItsValue() = myFixture.runWithConfig(Config()) {
        configure("inlay", "Hello")
        val hints = collectHints()
        assertEquals(1, hints.size, "A resolved key must produce exactly one hint")
        assertTrue(hints.single().contains("Hello"), "The hint must carry the translation: ${hints.single()}")
    }

    @Test
    fun testUnresolvedKeyProducesNoHint() = myFixture.runWithConfig(Config()) {
        configure("missing", "Hello", key = "root.first.absent")
        assertTrue(collectHints().isEmpty(), "An unresolved key must not produce a hint")
    }

    @Test
    fun testKeyWithoutAnyTranslationFileProducesNoHint() = myFixture.runWithConfig(Config()) {
        myFixture.configureByText("orphan.js", JsCodeGenerator().generate("\"nowhere:root.first.second\"", 0))
        assertTrue(collectHints().isEmpty(), "A key with no translation file must not produce a hint")
    }

    @Test
    fun testValueIsTruncatedToFoldingMaxLength() = myFixture.runWithConfig(Config(foldingMaxLength = 20)) {
        configure("long", "0123456789012345678901234567890123456789")
        val hint = collectHints().single()
        assertTrue(hint.contains("01234567890123456789..."), "The value must be truncated at 20 characters: $hint")
        assertFalse(hint.contains("0123456789012345678901"), "Nothing beyond the limit may survive: $hint")
    }

    /** Guards the ICU rendering wired into this provider: the raw source must never be shown. */
    @Test
    fun testIcuValueIsRenderedNotShownRaw() = myFixture.runWithConfig(Config()) {
        configure("icu", "{count, plural, one {# article} other {# articles}}")
        val hint = collectHints().single()
        assertTrue(hint.contains("{count} articles"), "The ICU message must be rendered: $hint")
        assertFalse(hint.contains("plural"), "The ICU source must not leak into the hint: $hint")
    }

    /** The preferred folding language selects the locale shown; other locales must stay out. */
    @Test
    fun testOnlyThePreferredLanguageIsShown() = myFixture.runWithConfig(Config(foldingPreferredLanguage = "en")) {
        val tg = JsonTranslationGenerator()
        addFileToProject("en/pref.${tg.ext()}", tg.generateContent("root", "first", "second", "Hello"))
        addFileToProject("fr/pref.${tg.ext()}", tg.generateContent("root", "first", "second", "Bonjour"))
        myFixture.configureByText("content_pref.js", JsCodeGenerator().generate("\"pref:root.first.second\"", 0))
        val hint = collectHints().single()
        assertTrue(hint.contains("Hello"), "The preferred language must be shown: $hint")
        assertFalse(hint.contains("Bonjour"), "Another locale must not be shown: $hint")
    }
}
