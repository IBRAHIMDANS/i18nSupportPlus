package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*

/**
 * Setup wizard dialog shown on first launch when no i18n config is detected.
 * Guides the user through 3 steps:
 *   1. Framework detection (i18next / vue-i18n / lingui / react-intl, see [FrameworkDetector])
 *   2. Translation file discovery (.json/.yaml/.po/.pot in locales, i18n, translations folders)
 *   3. Summary before applying configuration
 */
class SetupWizardDialog(private val project: Project) : DialogWrapper(project) {

    companion object {
        private const val STEP_FRAMEWORK = "FRAMEWORK"
        private const val STEP_FILES = "FILES"
        private const val STEP_SUMMARY = "SUMMARY"
        private val STEPS = listOf(STEP_FRAMEWORK, STEP_FILES, STEP_SUMMARY)
    }

    // -- Step 1: Framework
    private val frameworkCheckboxes: Map<String, JCheckBox> =
        FrameworkDetector.FRAMEWORK_KEYS.keys.associateWith { id ->
            JCheckBox(FrameworkDetector.LABELS[id] ?: id)
        }

    // -- Step 2: Translation files
    private val foundFiles = mutableListOf<String>()
    private val fileListModel = DefaultListModel<String>()
    private val fileList = JBList(fileListModel)

    // -- Step 3: Summary
    private val summaryLabel = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
    }

    // Navigation
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private var currentStepIndex = 0
    private val stepIndicatorLabel = JBLabel("Step 1 of ${STEPS.size}")

    private val backButton = JButton("Back")
    private val nextButton = JButton("Next")
    private val skipButton = JButton("Skip")

    init {
        title = "i18n Support Plus — Setup Wizard"
        isResizable = true
        init()
        setOKButtonText("Apply")
        setCancelButtonText("Skip")
        detectFrameworks()
        scanTranslationFiles()
    }

    override fun createCenterPanel(): JComponent {
        buildStep1Panel()
        buildStep2Panel()
        buildStep3Panel()

        val navPanel = JPanel()
        navPanel.add(skipButton)
        navPanel.add(backButton)
        navPanel.add(nextButton)

        backButton.isEnabled = false
        nextButton.text = "Next"

        skipButton.addActionListener { doCancelAction() }
        backButton.addActionListener { navigateTo(currentStepIndex - 1) }
        nextButton.addActionListener {
            if (currentStepIndex < STEPS.size - 1) {
                navigateTo(currentStepIndex + 1)
            } else {
                doOKAction()
            }
        }

        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(500, 380)
        root.add(buildStepIndicator(), BorderLayout.NORTH)
        root.add(cardPanel, BorderLayout.CENTER)
        root.add(navPanel, BorderLayout.SOUTH)
        return root
    }

    // Hide default OK/Cancel buttons — we use custom nav buttons instead
    override fun createActions(): Array<Action> = emptyArray()

    private fun buildStepIndicator(): JComponent {
        stepIndicatorLabel.border = JBUI.Borders.empty(8, 12)
        return stepIndicatorLabel
    }

    private fun buildStep1Panel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(16)

        panel.add(JBLabel("<html><b>Step 1 — Detected Framework</b></html>"))
        panel.add(Box.createVerticalStrut(12))
        panel.add(JBLabel("Check the i18n framework(s) used in this project:"))
        panel.add(Box.createVerticalStrut(8))

        for (cb in frameworkCheckboxes.values) {
            panel.add(cb)
            panel.add(Box.createVerticalStrut(4))
        }

        cardPanel.add(panel, STEP_FRAMEWORK)
        return panel
    }

    private fun buildStep2Panel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(16)

        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.add(JBLabel("<html><b>Step 2 — Translation Files Found</b></html>"))
        header.add(Box.createVerticalStrut(8))
        header.add(JBLabel("Files detected in 'locales', 'i18n', 'translations' folders:"))
        header.add(Box.createVerticalStrut(8))

        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(fileList), BorderLayout.CENTER)

        cardPanel.add(panel, STEP_FILES)
        return panel
    }

    private fun buildStep3Panel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(16)

        val header = JBLabel("<html><b>Step 3 — Summary</b></html>")
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(summaryLabel), BorderLayout.CENTER)

        cardPanel.add(panel, STEP_SUMMARY)
        return panel
    }

    private fun navigateTo(index: Int) {
        currentStepIndex = index.coerceIn(0, STEPS.size - 1)
        cardLayout.show(cardPanel, STEPS[currentStepIndex])
        stepIndicatorLabel.text = "Step ${currentStepIndex + 1} of ${STEPS.size}"
        backButton.isEnabled = currentStepIndex > 0
        nextButton.text = if (currentStepIndex == STEPS.size - 1) "Apply" else "Next"
        if (currentStepIndex == STEPS.size - 1) {
            refreshSummary()
        }
    }

    // -- Detection logic

    /**
     * Reads package.json at the project root and pre-checks framework checkboxes accordingly.
     */
    private fun detectFrameworks() {
        val packageJson = File(project.basePath ?: return, "package.json")
        if (!packageJson.exists()) return
        for (key in FrameworkDetector.detect(packageJson.readText())) {
            frameworkCheckboxes[key]?.isSelected = true
        }
    }

    /**
     * Scans the project for translation files inside the known folder names, through
     * [TranslationFileScanner] so the shipped tables are the ones under test.
     */
    private fun scanTranslationFiles() {
        val base = File(project.basePath ?: return)
        foundFiles.clear()
        fileListModel.clear()
        for (relative in TranslationFileScanner.scan(base)) {
            foundFiles.add(relative)
            fileListModel.addElement(relative)
        }
    }

    private fun refreshSummary() {
        val selectedFrameworks = frameworkCheckboxes
            .filterValues { it.isSelected }
            .keys
            .joinToString(", ")
            .ifEmpty { "(none)" }
            .let { StringUtil.escapeXmlEntities(it) }

        val fileCount = foundFiles.size
        val fileSample = foundFiles.take(5).joinToString("<br>") { "• ${StringUtil.escapeXmlEntities(it)}" }
        val moreNote = if (fileCount > 5) "<br>... and ${fileCount - 5} more" else ""

        val hasPo = foundFiles.any { it.endsWith(".po") || it.endsWith(".pot") }
        val poNote = if (hasPo)
            "<p style=\"color:#CC7700\">⚠ PO/POT files detected. Full support requires the " +
            "<b>GNU GetText</b> plugin (<i>Settings → Plugins → Marketplace → \"GNU GetText files support\"</i>).</p>"
        else ""

        // Announcing "Apply stores the root" while none could be derived is what made the
        // wizard look like it had configured the project when it had written nothing.
        val detectedRoot = TranslationRootDetector.detect(foundFiles)
        val rootNote = when {
            fileCount == 0 -> ""
            detectedRoot == null ->
                "<p style=\"color:#CC7700\">⚠ No common translation root could be derived from these files, " +
                "so <b>Apply</b> will not set one. Point the plugin at your translations in " +
                "<i>Settings → Tools → i18n Support Plus Configuration</i>.</p>"
            else -> "<p><b>Translations root:</b> ${StringUtil.escapeXmlEntities(detectedRoot)}</p>"
        }

        // The ticked frameworks used to feed this sentence and nothing else. Listing what they
        // actually change is what makes the checkboxes mean something.
        val settingsNote = describeDeducedSettings()

        val closing = if (fileCount > 0 && detectedRoot != null)
            "<p style=\"color:gray\">Clicking <b>Apply</b> will store these settings. " +
            "You can adjust them in <i>Settings → Tools → i18n Support Plus Configuration</i>.</p>"
        else ""

        val html = "<html><body style=\"font-family:sans-serif\">" +
            "<p><b>Frameworks detected:</b> $selectedFrameworks</p>" +
            "<p><b>Translation files found:</b> $fileCount file(s)</p>" +
            (if (fileCount > 0) "<p>$fileSample$moreNote</p>" else "") +
            rootNote +
            settingsNote +
            poNote +
            closing +
            "</body></html>"
        summaryLabel.text = html
    }

    /**
     * Lists the settings *Apply* is about to write, or nothing when it would write none.
     *
     * Only the ones that will actually be stored are listed: a field the user already changed
     * is left alone, so announcing it would be another promise the wizard does not keep.
     */
    private fun describeDeducedSettings(): String {
        val deduced = WizardSettingsDeducer.deduce(foundFiles, selectedFrameworks())
        if (deduced.isEmpty()) return ""

        val settings = Settings.getInstance(project)
        val defaults = Config()
        val rows = buildList {
            deduced.defaultNs
                ?.takeIf { settings.defaultNs == defaults.defaultNs }
                ?.let { add("Default namespace: <b>${StringUtil.escapeXmlEntities(it)}</b>") }
            deduced.gettext
                ?.takeIf { settings.gettext == defaults.gettext }
                ?.let { add("GetText mode: <b>on</b>") }
            deduced.flatKeys
                ?.takeIf { settings.flatKeys == defaults.flatKeys }
                ?.let { add("Treat keys as flat: <b>on</b> (react-intl stores flat ids)") }
            deduced.preferredLocalization
                ?.takeIf { WizardSettingsDeducer.isUntouchedPreferredLocalization(settings.preferredLocalization) }
                ?.let { add("Preferred format: <b>${StringUtil.escapeXmlEntities(it)}</b>") }
        }
        if (rows.isEmpty()) return ""
        return "<p><b>Settings to apply:</b></p><p>" + rows.joinToString("<br>") { "• $it" } + "</p>"
    }

    /**
     * Applies the detected configuration to Settings when the user clicks Apply.
     */
    override fun doOKAction() {
        val settings = Settings.getInstance(project)
        settings.wizardDismissed = true

        // foundFiles holds paths relative to the project, and translationsRoot is read back as
        // "$basePath/$translationsRoot", so what is stored here must stay relative too.
        TranslationRootDetector.detect(foundFiles)?.let { settings.translationsRoot = it }
        applyDeducedSettings(settings)

        super.doOKAction()
    }

    /**
     * Applies what the scan and the ticked frameworks imply, without ever overwriting a value
     * the user already changed.
     *
     * The wizard opens on first launch, but nothing guarantees it writes first: settings can
     * be edited before it is answered, or it can be reopened later. Each field is therefore
     * written only while it still holds the default from [Config] — which is also why
     * `Deduced` uses nulls rather than falling back to defaults itself.
     */
    private fun applyDeducedSettings(settings: Settings) {
        val deduced = WizardSettingsDeducer.deduce(foundFiles, selectedFrameworks())
        val defaults = Config()

        deduced.defaultNs
            ?.takeIf { settings.defaultNs == defaults.defaultNs }
            ?.let { settings.defaultNs = it }

        deduced.gettext
            ?.takeIf { settings.gettext == defaults.gettext }
            ?.let { settings.gettext = it }

        deduced.flatKeys
            ?.takeIf { settings.flatKeys == defaults.flatKeys }
            ?.let { settings.flatKeys = it }

        deduced.preferredLocalization
            ?.takeIf { WizardSettingsDeducer.isUntouchedPreferredLocalization(settings.preferredLocalization) }
            ?.let { settings.preferredLocalization = it }
    }

    private fun selectedFrameworks(): Set<String> =
        frameworkCheckboxes.filterValues { it.isSelected }.keys

    override fun doCancelAction() {
        Settings.getInstance(project).wizardDismissed = true
        super.doCancelAction()
    }
}
