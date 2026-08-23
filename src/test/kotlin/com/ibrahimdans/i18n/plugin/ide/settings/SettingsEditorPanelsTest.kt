package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JSpinner
import javax.swing.JTextField

/**
 * Finds the component named [name] anywhere under [root].
 *
 * The settings panel names every control after its bundle label, which is how the UI test
 * drives it. These tests hold that convention from the outside — it has to survive the
 * split of the two tables into master-detail forms.
 */
private fun findByName(root: Component, name: String): Component? {
    if (root.name == name) return root
    if (root !is Container) return null
    return root.components.firstNotNullOfOrNull { findByName(it, name) }
}

private fun requireByName(root: Component, key: String): Component {
    val label = PluginBundle.getMessage(key)
    val component = findByName(root, label)
    assertNotNull(component, "no component named '$label' (key $key)")
    return component!!
}

/**
 * Fails when the Add/Remove row is wider than the panel that has to hold it.
 *
 * `ListEditorPanel` puts its list and that row in a panel it hands to `BorderLayout.WEST`, and
 * BorderLayout gives a WEST child exactly its *preferred* width — never more. A preferred width
 * fixed without looking at the row therefore clips it, and the second button is the one that
 * loses: *Remove module* reaches the screen as "Remove m…".
 *
 * Asserted here because nothing else can catch it. No test renders a Swing component, so the
 * defect shipped invisible to the suite; and both labels are translated, so a width that happens
 * to fit in English still clips in a language whose words are longer.
 */
private fun assertButtonRowFits(root: Component, addButtonName: String) {
    val add = findByName(root, addButtonName)
    assertNotNull(add, "no add button named '$addButtonName'")
    val row = (add as JButton).parent
    val master = row.parent

    assertTrue(
        master.preferredSize.width >= row.preferredSize.width,
        "the list panel is ${master.preferredSize.width}px wide for a ${row.preferredSize.width}px " +
            "button row: BorderLayout.WEST clips the difference"
    )
}

/**
 * Fails when an editor panel is pinned narrower than the form it has to hold.
 *
 * Both editors set `preferredSize = Dimension(700, …)` on themselves while their content — the
 * item list, the gap and the detail form — is laid out to whatever it actually needs. Anything
 * past 700px is not scrolled, it is simply cut off the right edge, which is how the field
 * comments reach the screen mid-sentence.
 */
private fun assertFormIsNotSqueezed(panel: javax.swing.JPanel, name: String) {
    val editor = panel.components.filterIsInstance<ListEditorPanel<*>>().single()
    assertTrue(
        panel.preferredSize.width >= editor.preferredSize.width,
        "$name is pinned to ${panel.preferredSize.width}px for content that needs " +
            "${editor.preferredSize.width}px: the difference is cut off, not scrolled"
    )
}

class ModulesEditorPanelTest {

    private val project = mockk<Project>()

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun panelWith(vararg modules: ModuleConfig): Pair<Settings, ModulesEditorPanel> {
        every { project.basePath } returns null
        val settings = Settings()
        settings.modules.addAll(modules)
        return settings to ModulesEditorPanel(settings, project)
    }

    @Test
    fun testEveryFieldIsNamedAfterItsBundleLabel() {
        val (_, panel) = panelWith(ModuleConfig(name = "frontend"))

        listOf(
            "settings.modules.name",
            "settings.modules.rootDirectory",
            "settings.modules.pathTemplate",
            "settings.modules.fileTemplate",
            "settings.modules.keyTemplate",
            "settings.modules.referenceLocale",
            "settings.modules.preset",
            "settings.modules.resolution"
        ).forEach { key -> requireByName(panel, key) }
    }

    @Test
    fun testThePresetIsPickedFromAListInsteadOfTyped() {
        val (_, panel) = panelWith(ModuleConfig(name = "frontend"))

        val preset = requireByName(panel, "settings.modules.preset")

        assertTrue(preset is JComboBox<*>, "the framework preset must be a combo box")
        assertTrue((preset as JComboBox<*>).itemCount > 1, "the combo must offer the known frameworks")
    }

    @Test
    fun testEditingAFieldReplacesTheModuleRatherThanMutatingIt() {
        val original = ModuleConfig(name = "frontend")
        val (settings, panel) = panelWith(original)

        (requireByName(panel, "settings.modules.name") as JTextField).text = "admin"

        assertEquals("admin", settings.modules[0].name)
        // Configurable snapshots the module list by copying the list, not its elements: an
        // in-place edit would be invisible to isModified and Apply would stay grey.
        assertEquals("frontend", original.name)
    }

    @Test
    fun testTheReferenceLocaleIsEditableFromTheForm() {
        val (settings, panel) = panelWith(ModuleConfig(name = "frontend"))

        (requireByName(panel, "settings.modules.referenceLocale") as JTextField).text = "fr"

        assertEquals("fr", settings.modules[0].referenceLocale)
    }

    @Test
    fun testTheModuleFormIsNotSqueezed() {
        assertFormIsNotSqueezed(panelWith(ModuleConfig(name = "frontend")).second, "the modules editor")
    }

    @Test
    fun testTheModuleButtonsAreNotClipped() {
        assertButtonRowFits(panelWith().second, "modules.add")
    }

    @Test
    fun testANewModuleComesWithATemplateRatherThanEmpty() {
        val (settings, panel) = panelWith()

        val addButton = findByName(panel, "modules.add")
        assertNotNull(addButton, "the modules list must keep its add button")
        (addButton as JButton).doClick()

        assertEquals(1, settings.modules.size)
        assertTrue(settings.modules[0].name.isNotBlank(), "a new module must be named")
        assertTrue(settings.modules[0].pathTemplate.isNotBlank(), "a new module must show the expected template")
    }
}

class RulesEditorPanelTest {

    private fun panelWith(vararg rules: EditorRuleState): Pair<Settings, RulesEditorPanel> {
        val settings = Settings()
        settings.rules.addAll(rules)
        return settings to RulesEditorPanel(settings)
    }

    @Test
    fun testEveryFieldIsNamedAfterItsBundleLabel() {
        val (_, panel) = panelWith(EditorRuleState(id = "first"))

        listOf(
            "settings.rules.col.id",
            "settings.rules.col.language",
            "settings.rules.col.trigger",
            "settings.rules.col.priority",
            "settings.rules.col.exclude",
            "settings.rules.col.type",
            "settings.rules.col.value",
            "settings.rules.col.matchMode",
            "settings.rules.col.negated"
        ).forEach { key -> requireByName(panel, key) }
    }

    @Test
    fun testTheFlagsAreCheckboxesSoTheyCannotBeMistyped() {
        val (settings, panel) = panelWith(EditorRuleState(id = "first"))

        val exclude = requireByName(panel, "settings.rules.col.exclude")
        assertTrue(exclude is JCheckBox, "exclude must be a checkbox")
        (exclude as JCheckBox).isSelected = true

        assertTrue(settings.rules[0].exclude)
    }

    @Test
    fun testThePriorityIsANumberFieldSoATypoCannotSilentlyMeanZero() {
        val (settings, panel) = panelWith(EditorRuleState(id = "first", priority = 3))

        val priority = requireByName(panel, "settings.rules.col.priority")
        assertTrue(priority is JSpinner, "priority must be a number field")
        val spinner = priority as JSpinner
        assertEquals(3, spinner.value as Int)
        spinner.value = 7

        assertEquals(7, settings.rules[0].priority)
    }

    @Test
    fun testTheRuleFormIsNotSqueezed() {
        assertFormIsNotSqueezed(panelWith(EditorRuleState(id = "first")).second, "the rules editor")
    }

    @Test
    fun testTheRuleButtonsAreNotClipped() {
        assertButtonRowFits(panelWith().second, "rules.add")
    }
}
