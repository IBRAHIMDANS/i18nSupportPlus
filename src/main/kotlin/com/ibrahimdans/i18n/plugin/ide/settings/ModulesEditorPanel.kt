package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.IssueKind
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.RootStatus
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.TemplateIssue
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleTemplateResolver.TemplateResolution
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Modules editor: the configured modules on the left, one typed form on the right.
 *
 * It replaces a six-column table of free text where a framework name, a directory and three
 * path templates were all typed blind. Here the framework is picked from a list, the root
 * directory has a folder chooser, and the templates are shown resolving to the file they
 * actually produce — so a typo is visible where it is made rather than at runtime.
 */
internal class ModulesEditorPanel(
    private val settings: Settings,
    private val project: Project
) : ItemEditorPanel<ModuleConfig>() {

    private val nameField = boundTextField(PluginBundle.message("settings.modules.name"), 20) { module, value ->
        module.copy(name = value)
    }

    private val rootField = boundTextField(PluginBundle.message("settings.modules.rootDirectory"), 24) { module, value ->
        module.copy(rootDirectory = value)
    }

    private val pathField = boundTextField(PluginBundle.message("settings.modules.pathTemplate"), 24) { module, value ->
        module.copy(pathTemplate = value)
    }

    private val fileField = boundTextField(PluginBundle.message("settings.modules.fileTemplate"), 24) { module, value ->
        module.copy(fileTemplate = value)
    }

    private val keyField = boundTextField(PluginBundle.message("settings.modules.keyTemplate"), 24) { module, value ->
        module.copy(keyTemplate = value)
    }

    private val referenceLocaleField =
        boundTextField(PluginBundle.message("settings.modules.referenceLocale"), 8) { module, value ->
            module.copy(referenceLocale = value)
        }

    private val presetCombo = createPresetCombo()

    private val browseButton = createBrowseButton()

    private val resolutionArea = createResolutionArea()

    init {
        editor = ListEditorPanel(
            items = settings.modules,
            detailForm = detailForm(),
            newItem = ::newModule,
            labelOf = ::label,
            listName = "modules.list",
            addLabel = PluginBundle.message("settings.modules.add"),
            addName = "modules.add",
            removeLabel = PluginBundle.message("settings.modules.remove"),
            removeName = "modules.remove",
            onSelectionChanged = ::bind
        )
        add(editor, BorderLayout.CENTER)
        preferredSize = Dimension(700, 260)

        bind(null)
        editor.selectFirst()
    }

    // --- form ---

    private fun detailForm(): JPanel = panel {
        row(PluginBundle.message("settings.modules.name")) { cell(nameField) }
        row(PluginBundle.message("settings.modules.preset")) { cell(presetCombo) }
        row(PluginBundle.message("settings.modules.rootDirectory")) {
            cell(rootField)
            cell(browseButton)
        }
        row(PluginBundle.message("settings.modules.pathTemplate")) {
            cell(pathField).comment(PluginBundle.message("settings.modules.pathTemplate.comment"))
        }
        row(PluginBundle.message("settings.modules.fileTemplate")) { cell(fileField) }
        row(PluginBundle.message("settings.modules.keyTemplate")) { cell(keyField) }
        row(PluginBundle.message("settings.modules.referenceLocale")) {
            cell(referenceLocaleField).comment(PluginBundle.message("settings.modules.referenceLocale.comment"))
        }
        group(PluginBundle.message("settings.modules.resolution")) {
            row { cell(resolutionArea).align(AlignX.FILL) }
        }
    }

    private fun createResolutionArea(): JTextArea {
        val area = JTextArea(4, 46)
        area.name = PluginBundle.message("settings.modules.resolution")
        area.isEditable = false
        area.isOpaque = false
        area.lineWrap = true
        area.wrapStyleWord = true
        return area
    }

    private fun createPresetCombo(): JComboBox<String> {
        val combo = JComboBox<String>()
        combo.name = PluginBundle.message("settings.modules.preset")
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                (component as JLabel).text = presetLabel(value as? String ?: "")
                return component
            }
        }
        combo.addActionListener {
            mutate { module -> module.copy(preset = combo.selectedItem as? String ?: "") }
        }
        return combo
    }

    private fun createBrowseButton(): JButton {
        val button = JButton(PluginBundle.message("settings.modules.browse"))
        button.name = "modules.root.browse"
        button.addActionListener { chooseRootDirectory() }
        return button
    }

    private fun chooseRootDirectory() {
        val chosen = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
            project,
            null
        ) ?: return
        // The field is the single source of truth: writing to it runs the usual binding.
        rootField.text = relativize(chosen.path)
    }

    /** A path under the project directory is stored relative to it, like every other root. */
    private fun relativize(path: String): String {
        val base = basePath() ?: return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    // --- selection ---

    private fun bind(module: ModuleConfig?) = load(module) { selected ->
        nameField.text = selected?.name ?: ""
        rootField.text = selected?.rootDirectory ?: ""
        pathField.text = selected?.pathTemplate ?: ""
        fileField.text = selected?.fileTemplate ?: ""
        keyField.text = selected?.keyTemplate ?: ""
        referenceLocaleField.text = selected?.referenceLocale ?: ""
        presetCombo.model = DefaultComboBoxModel(presetItems(selected?.preset ?: ""))
        presetCombo.selectedItem = selected?.preset ?: ""
        setFormEnabled(selected != null)
    }

    private fun setFormEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        rootField.isEnabled = enabled
        pathField.isEnabled = enabled
        fileField.isEnabled = enabled
        keyField.isEnabled = enabled
        referenceLocaleField.isEnabled = enabled
        presetCombo.isEnabled = enabled
        browseButton.isEnabled = enabled
    }

    override fun onItemChanged() {
        resolutionArea.text = report(editor.selected())
    }

    // --- list rendering ---

    /** A module whose root directory has disappeared is flagged in the list itself. */
    private fun label(module: ModuleConfig): String {
        val name = module.name.ifBlank { PluginBundle.message("settings.modules.unnamed") }
        return if (ModuleTemplateResolver.rootStatus(module, basePath()) == RootStatus.MISSING) {
            PluginBundle.message("settings.modules.root.missing.marker", name)
        } else {
            name
        }
    }

    private fun newModule(): ModuleConfig {
        val taken = settings.modules.map { it.name }.toSet()
        var index = settings.modules.size + 1
        while (taken.contains(PluginBundle.message("settings.modules.default.name", index))) index++
        // Pre-filled with the shape a module is expected to have: an empty row taught nothing.
        return ModuleConfig(
            name = PluginBundle.message("settings.modules.default.name", index),
            pathTemplate = "{lang}/{ns}.json"
        )
    }

    // --- resolution report ---

    private fun report(module: ModuleConfig?): String {
        if (module == null) return PluginBundle.message("settings.modules.resolution.noSelection")

        val resolution = ModuleTemplateResolver.describe(module, sampleLocale(module), sampleNamespace(), basePath())
        val lines = mutableListOf<String>()
        if (resolution.template.isBlank()) {
            lines.add(PluginBundle.message("settings.modules.resolution.empty"))
        } else {
            lines.add(
                PluginBundle.message(
                    "settings.modules.resolution.mapping",
                    resolution.template,
                    resolution.resolvedPath
                )
            )
            lines.add(lookupLine(resolution))
        }
        resolution.issues
            .filter { it.kind != IssueKind.BLANK }
            .forEach { lines.add(issueMessage(it)) }
        return lines.joinToString("\n")
    }

    private fun lookupLine(resolution: TemplateResolution): String {
        val absolutePath = resolution.absolutePath
            ?: return PluginBundle.message("settings.modules.resolution.unknownBase")
        return when {
            resolution.exists -> PluginBundle.message("settings.modules.resolution.found", absolutePath)
            resolution.neighbours.isNotEmpty() -> PluginBundle.message(
                "settings.modules.resolution.notFoundNearby",
                absolutePath,
                resolution.neighbours.joinToString(", ")
            )
            else -> PluginBundle.message("settings.modules.resolution.notFound", absolutePath)
        }
    }

    private fun issueMessage(issue: TemplateIssue): String = when (issue.kind) {
        IssueKind.BLANK -> PluginBundle.message("settings.modules.resolution.empty")
        IssueKind.UNBALANCED_BRACES -> PluginBundle.message("settings.modules.issue.unbalanced")
        IssueKind.UNKNOWN_PLACEHOLDER -> PluginBundle.message(
            "settings.modules.issue.unknownPlaceholder",
            issue.argument,
            ModuleTemplateResolver.SUPPORTED_PLACEHOLDERS.joinToString(", ")
        )
        IssueKind.NO_LANGUAGE_PLACEHOLDER -> PluginBundle.message("settings.modules.issue.noLanguage")
    }

    /** The locale the preview resolves with: the module's own, else the global preview one. */
    private fun sampleLocale(module: ModuleConfig): String =
        module.referenceLocale
            .ifBlank { settings.previewLocale }
            .ifBlank { settings.foldingPreferredLanguage }
            .ifBlank { "en" }

    private fun sampleNamespace(): String =
        settings.defaultNs
            .split(',', ';', ' ')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "common"

    // --- presets ---

    private fun presetLabel(preset: String): String =
        if (preset.isEmpty()) PluginBundle.message("settings.modules.preset.none")
        else (FrameworkDetector.LABELS[preset] ?: preset)

    /** The known presets, plus the stored one when it comes from a newer or hand-written config. */
    private fun presetItems(current: String): Array<String> {
        val known = listOf("") + FrameworkDetector.LABELS.keys
        val items = if (current.isBlank() || known.contains(current)) known else known + current
        return items.toTypedArray()
    }

    /**
     * Read lazily on purpose: the settings panel is built in tests with a mock project, and
     * only a configured module ever asks where the project lives.
     */
    private fun basePath(): String? = project.basePath
}
