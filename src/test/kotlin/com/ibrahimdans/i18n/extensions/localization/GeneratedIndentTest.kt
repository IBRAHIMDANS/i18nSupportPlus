package com.ibrahimdans.i18n.extensions.localization

import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.extensions.localization.yaml.YamlLocalization
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.openapi.command.WriteCommandAction
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping
import com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The indentation of generated keys follows each format's own setting, stored in
 * `localizationConfig`, which nothing used to declare, show or read.
 */
class GeneratedIndentTest : PlatformBaseTest() {

    private val key = FullKey("a.b.c", null, listOf(Literal("a"), Literal("b"), Literal("c")))

    private fun withSetting(entries: Map<String, String>, block: () -> Unit) {
        val settings = Settings.getInstance(project)
        val original = settings.localizationConfig
        settings.localizationConfig = entries
        try { block() } finally { settings.localizationConfig = original }
    }

    @Test
    fun yamlUsesTheConfiguredIndent() = withSetting(mapOf("yaml/indent" to "4")) {
        val file = myFixture.configureByText("en.yml", "x: y\n") as YAMLFile
        val mapping = PsiTreeUtil.findChildOfType(file, YAMLMapping::class.java)!!
        WriteCommandAction.runWriteCommandAction(project) {
            YamlLocalization().contentGenerator().generate(mapping, key, key.compositeKey, "v")
        }
        assertTrue(file.text.contains("\n    c: v"), file.text)
    }

    /** JSON's indentation belongs to the IDE's JSON code style, which reformats generated content. */
    @Test
    fun onlyYamlDeclaresTheIndentProperty() {
        assertEquals(listOf("indent"), YamlLocalization().config().props().map { it.id })
        assertTrue(JsonLocalization().config().props().isEmpty())
    }
}
