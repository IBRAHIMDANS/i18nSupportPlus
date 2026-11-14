package com.ibrahimdans.i18n.plugin.mcp

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentInstructionsTest {

    private val block = AgentInstructions.block(listOf("public/locales"))

    @Test
    fun `the block names the folders and sits between its markers`() {
        assertEquals(
            "${AgentInstructions.BEGIN}\n" +
                "For translation files (public/locales), use the i18n_* MCP tools instead of editing the files.\n" +
                "${AgentInstructions.END}\n",
            block,
        )
    }

    @Test
    fun `without a folder the block names none`() {
        assertTrue("For translation files, use the i18n_* MCP tools" in AgentInstructions.block(emptyList()))
    }

    @Test
    fun `an empty file becomes the block alone`() {
        assertEquals(block, AgentInstructions.merge("", block))
        assertEquals(block, AgentInstructions.merge("\n", block))
    }

    @Test
    fun `the block goes after the text, a blank line apart, the text kept`() {
        assertEquals("# Project\n\nRules.\n\n$block", AgentInstructions.merge("# Project\n\nRules.\n", block))
        assertEquals("Rules.\n\n$block", AgentInstructions.merge("Rules.", block))
        assertEquals("Rules.\n\n$block", AgentInstructions.merge("Rules.\n\n", block))
    }

    @Test
    fun `a block written before is replaced, once, and the text around it kept`() {
        val before = "# Project\n\n${AgentInstructions.block(listOf("old"))}\n## Tests\n"
        val merged = AgentInstructions.merge(before, block)

        assertEquals("# Project\n\n$block\n## Tests\n", merged)
        assertEquals(1, Regex(Regex.escape(AgentInstructions.BEGIN)).findAll(merged).count())
        assertEquals(merged, AgentInstructions.merge(merged, block), "running it again changes nothing")
    }

    @Test
    fun `the translations root names the folder without modules`() {
        assertEquals(listOf("src/locales"), AgentInstructions.translationFolders(Config(translationsRoot = "./src/locales/")))
        assertEquals(emptyList<String>(), AgentInstructions.translationFolders(Config()))
    }

    @Test
    fun `each active module gives its root and the fixed start of its path template`() {
        val config = Config(
            modules = listOf(
                ModuleConfig(name = "web", rootDirectory = "apps/web", pathTemplate = "locales/{lang}/{ns}.json"),
                ModuleConfig(name = "admin", rootDirectory = "apps/admin/", pathTemplate = "{lang}.json"),
                ModuleConfig(name = "root", rootDirectory = "", pathTemplate = "public/locales/{lang}/{ns}.json"),
                ModuleConfig(name = "off", rootDirectory = "apps/old", pathTemplate = "locales/{lang}.json", enabled = false),
                ModuleConfig(name = "twin", rootDirectory = "apps/web", pathTemplate = "locales/{lang}.json"),
            ),
        )
        assertEquals(listOf("apps/web/locales", "apps/admin", "public/locales"), AgentInstructions.translationFolders(config))
    }

    @Test
    fun `the action is declared with the MCP server only`() {
        val id = "com.ibrahimdans.i18n.WriteAgentInstructions"
        assertTrue(id in File("src/main/resources/META-INF/mcpConfig.xml").readText())
        assertFalse(id in File("src/main/resources/META-INF/plugin.xml").readText())
    }
}
