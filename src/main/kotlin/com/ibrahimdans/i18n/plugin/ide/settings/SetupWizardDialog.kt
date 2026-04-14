package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
 *   1. Framework detection (i18next / vue-i18n / lingui)
 *   2. Translation file discovery (.json/.yaml in locales, i18n, translations folders)
 *   3. Summary before applying configuration
 */
class SetupWizardDialog(private val project: Project) : DialogWrapper(project) {

    companion object {
        private val FRAMEWORK_KEYS = mapOf(
            "i18next" to listOf("i18next", "react-i18next"),
            "vue-i18n" to listOf("vue-i18n"),
            "lingui" to listOf("@lingui/core", "@lingui/react", "@lingui/macro", "@lingui/react/macro")
        )
        private val TRANSLATION_FOLDER_NAMES = setOf("locales", "i18n", "translations")
        private val TRANSLATION_EXTENSIONS = setOf("json", "yaml", "yml", "po", "pot")
        private const val MAX_SCAN_DEPTH = 5
        private const val STEP_FRAMEWORK = "FRAMEWORK"
        private const val STEP_FILES = "FILES"
        private const val STEP_SUMMARY = "SUMMARY"
        private val STEPS = listOf(STEP_FRAMEWORK, STEP_FILES, STEP_SUMMARY)
    }

    // -- Step 1: Framework
    private val frameworkCheckboxes: Map<String, JCheckBox> = mapOf(
        "i18next" to JCheckBox("i18next / react-i18next"),
        "vue-i18n" to JCheckBox("vue-i18n"),
        "lingui" to JCheckBox("lingui")
    )

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
        val content = packageJson.readText()
        for ((key, deps) in FRAMEWORK_KEYS) {
            if (deps.any { content.contains("\"$it\"") }) {
                frameworkCheckboxes[key]?.isSelected = true
            }
        }
    }

    /**
     * Recursively scans the project for translation files inside known folder names.
     */
    private fun scanTranslationFiles() {
        val base = File(project.basePath ?: return)
        foundFiles.clear()
        fileListModel.clear()
        collectTranslationFiles(base, base, 0)
    }

    private fun collectTranslationFiles(base: File, dir: File, depth: Int) {
        if (depth > MAX_SCAN_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in TRANSLATION_FOLDER_NAMES) {
                    // List all .json/.yaml/.yml files inside
                    collectAllTranslations(base, child)
                } else if (!child.name.startsWith(".") && child.name != "node_modules" && child.name != "build") {
                    collectTranslationFiles(base, child, depth + 1)
                }
            }
        }
    }

    private fun collectAllTranslations(base: File, folder: File) {
        folder.walkTopDown()
            .filter { it.isFile && it.extension in TRANSLATION_EXTENSIONS }
            .forEach { file ->
                val relative = file.relativeTo(base).path
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

        val fileCount = foundFiles.size
        val fileSample = foundFiles.take(5).joinToString("<br>") { "• $it" }
        val moreNote = if (fileCount > 5) "<br>... and ${fileCount - 5} more" else ""

        val html = "<html><body style=\"font-family:sans-serif\">" +
            "<p><b>Frameworks detected:</b> $selectedFrameworks</p>" +
            "<p><b>Translation files found:</b> $fileCount file(s)</p>" +
            (if (fileCount > 0) "<p>$fileSample$moreNote</p>" else "") +
            "<p style=\"color:gray\">Clicking <b>Apply</b> will store the detected translation root path " +
            "in the plugin settings. You can adjust further in <i>Settings → Tools → i18n Support Plus Configuration</i>.</p>" +
            "</body></html>"
        summaryLabel.text = html
    }

    /**
     * Applies the detected configuration to Settings when the user clicks Apply.
     */
    override fun doOKAction() {
        val settings = Settings.getInstance(project)
        settings.wizardDismissed = true

        // Determine the most common parent folder of found translation files
        if (foundFiles.isNotEmpty()) {
            val rootGuess = foundFiles
                .mapNotNull { File(it).parentFile?.parentFile?.path }
                .groupBy { it }
                .maxByOrNull { it.value.size }
                ?.key ?: ""
            if (rootGuess.isNotEmpty()) {
                settings.translationsRoot = rootGuess
            }
        }

        super.doOKAction()
    }

    override fun doCancelAction() {
        Settings.getInstance(project).wizardDismissed = true
        super.doCancelAction()
    }
}
