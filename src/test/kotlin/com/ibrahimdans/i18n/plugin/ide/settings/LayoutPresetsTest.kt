package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.IssueKind
import com.ibrahimdans.i18n.plugin.parser.KeyTemplate
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * Choosing a module preset fills the module's file layout, as the framework documents it, without
 * ever losing templates typed by hand.
 */
class LayoutPresetsTest {

    private val project = mockk<Project>()

    /** How many times the overwrite confirmation was asked, and what it answers. */
    private var asked = 0
    private var answer = false

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun everyLayoutBelongsToAKnownPresetAndResolves() {
        assertTrue(LayoutPresets.all.isNotEmpty(), "the layouts must be read from the resource")
        LayoutPresets.all.forEach { (preset, layout) ->
            assertTrue(preset in FrameworkDetector.LABELS, "$preset is not a preset of the settings")
            val template = listOf(layout.pathTemplate, layout.fileTemplate).filter { it.isNotBlank() }.joinToString("/")
            assertEquals(emptyList<IssueKind>(), ModuleTemplateResolver.issues(template).map { it.kind }, "$preset: $template")
            assertTrue(KeyTemplate.parse(layout.keyTemplate) != null, "$preset: key template '${layout.keyTemplate}'")
        }
    }

    @Test
    fun onlyTemplatesTypedByHandAreProtected() {
        assertTrue(LayoutPresets.isReplaceable(ModuleConfig()), "empty")
        assertTrue(LayoutPresets.isReplaceable(ModuleConfig(pathTemplate = LayoutPresets.NEW_MODULE_PATH_TEMPLATE)), "a new module's default")
        assertTrue(LayoutPresets.isReplaceable(LayoutPresets.apply(ModuleConfig(), LayoutPresets.of("vue-i18n")!!)), "another preset's layout")
        assertFalse(LayoutPresets.isReplaceable(ModuleConfig(pathTemplate = "i18n/{lang}.yml")), "typed by hand")
    }

    @Test
    fun aModuleLeavingItsPresetLayoutIsCustom() {
        val i18next = LayoutPresets.apply(ModuleConfig(preset = "i18next"), LayoutPresets.of("i18next")!!)
        assertFalse(LayoutPresets.isCustom(i18next))
        assertTrue(LayoutPresets.isCustom(i18next.copy(pathTemplate = "locales/{lang}/{ns}.json")))
        assertFalse(LayoutPresets.isCustom(ModuleConfig(preset = "svelte-i18n", pathTemplate = "x/{lang}.json")), "no layout, never custom")
    }

    private fun panelWith(module: ModuleConfig): Pair<Settings, ModulesEditorPanel> {
        every { project.basePath } returns null
        val settings = Settings()
        settings.modules.add(module)
        return settings to ModulesEditorPanel(settings, project) { _, _ -> asked++; answer }
    }

    @Suppress("UNCHECKED_CAST")
    private fun presetCombo(panel: ModulesEditorPanel) =
        find(panel, PluginBundle.message("settings.modules.preset")) as JComboBox<String>

    private fun find(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }
    }

    @Test
    fun choosingAPresetFillsAnEmptyModule() {
        val (settings, panel) = panelWith(ModuleConfig(name = "web"))

        presetCombo(panel).selectedItem = "i18next"

        val module = settings.modules[0]
        assertEquals("i18next", module.preset)
        assertEquals("public/locales/{lang}/{ns}.json", module.pathTemplate)
        assertEquals("{ns}:{key}", module.keyTemplate)
        assertEquals(0, asked, "nothing typed by hand, nothing to confirm")
    }

    @Test
    fun aModuleTypedByHandIsLeftAloneWithoutConfirmation() {
        val (settings, panel) = panelWith(ModuleConfig(name = "web", pathTemplate = "i18n/{lang}.yml"))

        presetCombo(panel).selectedItem = "vue-i18n"

        assertEquals(1, asked)
        assertEquals("vue-i18n", settings.modules[0].preset, "the framework is stored either way")
        assertEquals("i18n/{lang}.yml", settings.modules[0].pathTemplate)
    }

    @Test
    fun aConfirmedOverwriteFillsTheModule() {
        answer = true
        val (settings, panel) = panelWith(ModuleConfig(name = "web", pathTemplate = "i18n/{lang}.yml"))

        presetCombo(panel).selectedItem = "vue-i18n"

        assertEquals("src/locales/{lang}.json", settings.modules[0].pathTemplate)
    }

    @Test
    fun editingAFieldAfterThePresetMakesItCustom() {
        val (settings, panel) = panelWith(ModuleConfig(name = "web"))
        presetCombo(panel).selectedItem = "i18next"

        (find(panel, PluginBundle.message("settings.modules.pathTemplate")) as JTextField).text = "locales/{lang}/{ns}.json"

        assertTrue(LayoutPresets.isCustom(settings.modules[0]))
        assertEquals("i18next", settings.modules[0].preset, "the stored preset does not change")
        val shown = presetCombo(panel).renderer
            .getListCellRendererComponent(javax.swing.JList<String>(), "i18next", -1, false, false) as javax.swing.JLabel
        assertEquals(PluginBundle.message("settings.modules.preset.custom", FrameworkDetector.LABELS["i18next"]!!), shown.text)
    }
}
