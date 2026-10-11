package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reference locale's value shown after each value of a translation file. The collector is
 * driven directly, as in [I18nInlayHintsProviderTest]; hints are captured with the text that
 * precedes them, so a test can tell which key got which hint.
 */
class ReferenceValueInlayHintsProviderTest : PlatformBaseTest() {

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
        val hints = mutableListOf<Pair<Int, String>>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            hints += ((position as? InlineInlayPosition)?.offset ?: -1) to CapturingBuilder().also(builder).text.toString()
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    /** The hints of the file at [path], keyed by the line they end, trimmed. */
    private fun hintsIn(path: String): Map<String, String> {
        val file = myFixture.findFileInTempDir(path) ?: error("no $path")
        myFixture.configureFromExistingVirtualFile(file)
        val sink = CapturingSink()
        read {
            val collector = ReferenceValueInlayHintsProvider()
                .createCollector(myFixture.file, myFixture.editor) as? SharedBypassCollector ?: return@read
            PsiTreeUtil.processElements(myFixture.file) { element ->
                collector.collectFromElement(element, sink)
                true
            }
        }
        val text = myFixture.editor.document.text
        return sink.hints.associate { (offset, hint) ->
            text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset).trim() to hint
        }
    }

    @Test
    fun `a JSON value shows the reference value of its key`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"save": "Save", "user": {"name": "Name"}}""")
        addFileToProject("locales/fr/common.json", "{\n\"save\": \"Enregistrer\",\n\"user\": {\n\"name\": \"Nom\"\n}\n}")
        val hints = hintsIn("locales/fr/common.json")
        assertEquals(mapOf("\"save\": \"Enregistrer\"" to "en: Save", "\"name\": \"Nom\"" to "en: Name"), hints)
    }

    @Test
    fun `a YAML value shows the reference value of its key`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.yml", "save: Save\nuser:\n  name: Name\n")
        addFileToProject("locales/fr/common.yml", "save: Enregistrer\nuser:\n  name: Nom\n")
        val hints = hintsIn("locales/fr/common.yml")
        assertEquals(mapOf("save: Enregistrer" to "en: Save", "name: Nom" to "en: Name"), hints)
    }

    @Test
    fun `the reference file itself gets no hint`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", """{"save": "Enregistrer"}""")
        assertTrue(hintsIn("locales/en/common.json").isEmpty())
    }

    @Test
    fun `a key the reference lacks says so`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"save": "Save"}""")
        addFileToProject("locales/fr/common.json", "{\n\"save\": \"Enregistrer\",\n\"extra\": \"En plus\"\n}")
        val hints = hintsIn("locales/fr/common.json")
        assertEquals(mapOf("\"save\": \"Enregistrer\"" to "en: Save", "\"extra\": \"En plus\"" to "missing in en"), hints)
    }

    @Test
    fun `a plural category the reference language lacks shows its other form`() = myFixture.runWithConfig(Config()) {
        addFileToProject("locales/en/common.json", """{"item_one": "One item", "item_other": "Items"}""")
        addFileToProject("locales/fr/common.json", "{\n\"item_one\": \"Un\",\n\"item_many\": \"Beaucoup\",\n\"item_other\": \"Des\"\n}")
        val hints = hintsIn("locales/fr/common.json")
        assertEquals(listOf("en: One item", "en: Items", "en: Items"), hints.values.toList())
    }

    @Test
    fun `a long reference value is cut as folding cuts it`() = myFixture.runWithConfig(Config(foldingMaxLength = 5)) {
        addFileToProject("locales/en/common.json", """{"hello": "Hello world"}""")
        addFileToProject("locales/fr/common.json", """{"hello": "Bonjour"}""")
        assertEquals(listOf("en: Hello..."), hintsIn("locales/fr/common.json").values.toList())
    }

    @Test
    fun `an ICU reference value is rendered as folding renders it`() = myFixture.runWithConfig(Config(foldingMaxLength = 100)) {
        val icu = "{count, plural, one {# item} other {# items}}"
        addFileToProject("locales/en/common.json", """{"items": "$icu"}""")
        addFileToProject("locales/fr/common.json", """{"items": "{count, plural, one {# article} other {# articles}}"}""")
        val hint = hintsIn("locales/fr/common.json").values.single()
        assertTrue(hint.startsWith("en: ") && hint != "en: $icu", hint)
    }

    /** In the light fixture, project files live under `src/`. */
    @Test
    fun `in a monorepo the reference is that of the file's module`() {
        val modules = listOf(
            ModuleConfig(name = "web", rootDirectory = "src/web", referenceLocale = "de"),
            ModuleConfig(name = "admin", rootDirectory = "src/admin", referenceLocale = "en"),
        )
        myFixture.runWithConfig(Config(modules = modules)) {
            addFileToProject("web/locales/en/common.json", """{"save": "Save"}""")
            addFileToProject("web/locales/de/common.json", """{"save": "Speichern"}""")
            addFileToProject("web/locales/fr/common.json", """{"save": "Enregistrer"}""")
            addFileToProject("admin/locales/en/common.json", """{"save": "Save"}""")
            addFileToProject("admin/locales/de/common.json", """{"save": "Speichern"}""")
            addFileToProject("admin/locales/fr/common.json", """{"save": "Enregistrer"}""")
            assertEquals(listOf("de: Speichern"), hintsIn("web/locales/fr/common.json").values.toList())
            assertEquals(listOf("en: Save"), hintsIn("admin/locales/fr/common.json").values.toList())
            assertTrue(hintsIn("web/locales/de/common.json").isEmpty(), "de is web's reference")
        }
    }

    @Test
    fun `declared on JSON and YAML under its own settings entry`() {
        val beans = InlayHintsProviderExtensionBean.EP.extensionList.filter { it.providerId == "i18n.reference.value.inlay" }
        assertTrue(beans.any { it.language == "JSON" }, beans.map { it.language }.toString())
        beans.forEach { assertEquals("OTHER_GROUP", it.requiredGroup().name) }
    }
}
