package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.JTextField

/**
 * A module's own layout can be suggested as a preset through a pre-filled GitHub issue, which must
 * never carry the user's paths or project name.
 */
class PresetSuggestionTest {

    private val custom = ModuleConfig(
        name = "secret-project",
        rootDirectory = "/home/someone/secret-project/apps/web",
        pathTemplate = "i18n/{lang}/{ns}.json",
        keyTemplate = "{ns}:{key}",
        preset = "i18next"
    )

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun decoded(url: String) = URLDecoder.decode(url, StandardCharsets.UTF_8)

    @Test
    fun theIssueCarriesTheTemplatesAndTheVersionOnly() {
        val url = PresetSuggestion.url(custom, "1.9.0")!!
        val text = decoded(url)

        assertTrue(url.startsWith("https://github.com/IBRAHIMDANS/i18nSupportPlus/issues/new?template=preset_request.md&"))
        assertTrue("`i18n/{lang}/{ns}.json`" in text)
        assertTrue("`{ns}:{key}`" in text)
        assertTrue("i18next / react-i18next" in text)
        assertTrue("1.9.0" in text)
        assertFalse("secret-project" in text, "neither the module name nor its root directory")
        assertFalse("/home/" in text, "no absolute path")
    }

    @Test
    fun specialCharactersAreEncoded() {
        val url = PresetSuggestion.url(custom.copy(pathTemplate = "my locales/{lang}/{ns}.json"), null)!!
        val query = url.substringAfter('?')

        listOf("{", "}", " ", "`", "\n").forEach { assertFalse(it in query, "'$it' must be encoded") }
        assertTrue("%20" in query, "spaces as %20, never +")
        assertFalse("+" in query)
        assertTrue("my locales/{lang}/{ns}.json" in decoded(url))
    }

    @Test
    fun aLongLayoutIsCutUnderTheUrlLimit() {
        val long = "a".repeat(3000) + "/{lang}/" + "b".repeat(3000) + "/{ns}.json"
        val url = PresetSuggestion.url(custom.copy(pathTemplate = long, fileTemplate = "é".repeat(2000)), null)!!

        assertTrue(url.length <= PresetSuggestion.MAX_URL_LENGTH, "${url.length}")
        assertTrue("_(truncated)_" in decoded(url))
    }

    @Test
    fun onlyACustomValidRelativeLayoutIsSuggested() {
        assertTrue(PresetSuggestion.canSuggest(custom))
        assertFalse(PresetSuggestion.canSuggest(LayoutPresets.apply(custom, LayoutPresets.of("i18next")!!)), "still the preset's layout")
        assertFalse(PresetSuggestion.canSuggest(custom.copy(preset = "")), "no preset")
        assertFalse(PresetSuggestion.canSuggest(custom.copy(pathTemplate = "i18n/{ns}.json")), "no language placeholder")
        assertFalse(PresetSuggestion.canSuggest(custom.copy(pathTemplate = "/abs/{lang}.json")), "absolute")
        assertFalse(PresetSuggestion.canSuggest(custom.copy(pathTemplate = "C:/i18n/{lang}.json")), "absolute on Windows")
        assertNull(PresetSuggestion.url(custom.copy(preset = ""), "1.9.0"))
    }

    private fun find(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }
    }

    @Test
    fun theLinkShowsOnlyForASuggestableModule() {
        val project = mockk<Project>()
        every { project.basePath } returns null
        val settings = Settings()
        settings.modules.add(LayoutPresets.apply(custom, LayoutPresets.of("i18next")!!))
        val panel = ModulesEditorPanel(settings, project)
        val link = find(panel, PluginBundle.message("settings.modules.preset.suggest"))
        assertNotNull(link)

        assertFalse(link!!.isVisible, "the preset's own layout: nothing to suggest")
        (find(panel, PluginBundle.message("settings.modules.pathTemplate")) as JTextField).text = "i18n/{lang}/{ns}.json"
        assertTrue(link.isVisible)
        (find(panel, PluginBundle.message("settings.modules.pathTemplate")) as JTextField).text = "i18n/{ns}.json"
        assertFalse(link.isVisible, "invalid template")
        assertEquals("i18n/{ns}.json", settings.modules[0].pathTemplate)
    }
}
