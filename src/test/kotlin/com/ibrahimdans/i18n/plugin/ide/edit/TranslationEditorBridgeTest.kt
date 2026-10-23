package com.ibrahimdans.i18n.plugin.ide.edit

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.edit.JsonTranslationEditor
import com.ibrahimdans.i18n.plugin.ide.settings.KeyOrder
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.command.WriteCommandAction
import org.jetbrains.yaml.psi.YAMLFile
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationEditorBridgeTest : PlatformBaseTest() {

    @Test
    fun `applies insertion and sorting to the document in one write command`() {
        val file = myFixture.configureByText("en.json", "{\"c\": \"C\", \"a\": \"A\"}")

        WriteCommandAction.runWriteCommandAction(project) {
            assertTrue(
                TranslationEditorBridge.insert(
                    file,
                    JsonTranslationEditor,
                    listOf("b"),
                    "B",
                    sort = KeyOrder.IGNORE_CASE.comparator,
                )
            )
        }

        myFixture.checkResult("{\"a\": \"A\", \"b\": \"B\", \"c\": \"C\"}")
    }

    @Test
    fun `uses the PSI fallback for JSON5 and YAML flow mappings`() {
        val json5 = myFixture.configureByText("en.json5", "{ // comment\n  key: 'A',\n}") as JsonFile
        val yaml = myFixture.configureByText("en.yml", "{a: A}") as YAMLFile
        val key = FullKey("next", null, listOf(Literal("next")))

        WriteCommandAction.runWriteCommandAction(project) {
            JsonLocalization().contentGenerator().generate(json5.topLevelValue!!, key, key.compositeKey, "N")
            YamlLocalization().contentGenerator().generate(yaml.documents.first().topLevelValue!!, key, key.compositeKey, "N")
        }

        assertTrue(json5.text.contains("next"))
        assertTrue(yaml.text.contains("next"))
    }
}
