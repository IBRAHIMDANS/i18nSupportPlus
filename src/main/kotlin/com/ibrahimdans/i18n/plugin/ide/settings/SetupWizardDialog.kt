package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.ide.wizard.AbstractWizard
import com.intellij.ide.wizard.CommitStepException
import com.intellij.ide.wizard.Step
import com.intellij.ide.wizard.StepAdapter
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Setup wizard shown on first launch when no i18n configuration is detected.
 *
 * Three steps: the frameworks in use, the translation files found, and a summary that is a
 * **form** rather than a paragraph — a root or a separator is corrected where it is read.
 *
 * Navigation comes from [AbstractWizard]. It used to be hand-rolled: three same-weight buttons
 * with the platform's own OK and Cancel hidden behind `createActions() = emptyArray()`, so
 * nothing said which one validated, Enter and Escape did not behave as they do in every other
 * IntelliJ wizard, and "Skip" was at once a navigation button and the cancel button. The SDK
 * gives all of that for free: one accented default button, a Previous that greys out on the
 * first step, and a Cancel that stays a cancel — relabelled here to say plainly that it leaves
 * without configuring anything.
 */
class SetupWizardDialog(private val project: Project) : AbstractWizard<Step>(
    PluginBundle.message("wizard.title"),
    project
) {

    // -- Step 1: Framework
    private val frameworkCheckboxes: Map<String, JCheckBox> =
        FrameworkDetector.FRAMEWORK_KEYS.keys.associateWith { id ->
            JCheckBox(FrameworkDetector.LABELS[id] ?: id)
        }

    // -- Step 2: Translation files
    private val foundFiles = mutableListOf<String>()
    private val fileListModel = DefaultListModel<String>()
    private val fileList = JBList(fileListModel)

    private val rail = StepRail(
        listOf(
            PluginBundle.message("wizard.rail.frameworks"),
            PluginBundle.message("wizard.rail.files"),
            PluginBundle.message("wizard.rail.summary")
        )
    )

    private val summaryStep = SummaryStep()

    init {
        isResizable = true
        addStep(FrameworkStep())
        addStep(FilesStep())
        addStep(summaryStep)
        init()
        // "Skip" named the same button that also moved between steps. The cancel button says
        // what leaving actually means, and stops competing with the accented default one.
        cancelButton.text = PluginBundle.message("wizard.button.later")
        detectFrameworks()
        scanTranslationFiles()
    }

    /** The wizard's own panel, with the step rail above it. */
    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(620, 460)
        root.add(rail, BorderLayout.NORTH)
        root.add(super.createCenterPanel(), BorderLayout.CENTER)
        return root
    }

    override fun updateStep() {
        super.updateStep()
        rail.select(currentStep)
    }

    override fun getHelpID(): String? = null

    // -- Steps

    private inner class FrameworkStep : StepAdapter() {
        private val component = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
            add(JBLabel(PluginBundle.message("wizard.step1.title")).apply { font = font.deriveFont(font.style or 1) })
            add(Box.createVerticalStrut(12))
            add(JBLabel(PluginBundle.message("wizard.step1.hint")))
            add(Box.createVerticalStrut(8))
            for (checkBox in frameworkCheckboxes.values) {
                add(checkBox)
                add(Box.createVerticalStrut(4))
            }
        }

        override fun getComponent(): JComponent = component
        override fun getPreferredFocusedComponent(): JComponent? = frameworkCheckboxes.values.firstOrNull()
    }

    private inner class FilesStep : StepAdapter() {
        private val component = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(PluginBundle.message("wizard.step2.title")).apply { font = font.deriveFont(font.style or 1) })
                add(Box.createVerticalStrut(8))
                add(JBLabel(PluginBundle.message("wizard.step2.hint")))
                add(Box.createVerticalStrut(8))
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(fileList), BorderLayout.CENTER)
        }

        override fun getComponent(): JComponent = component
        override fun getPreferredFocusedComponent(): JComponent = fileList
    }

    /**
     * The summary, as editable components.
     *
     * It used to be one concatenated HTML string: every value was readable and none was
     * correctable, so fixing a root meant walking back through steps that do not hold it in the
     * first place — the root is derived, not typed anywhere. Each value now sits in the field
     * that will be stored, next to the evidence it was read from.
     */
    private inner class SummaryStep : StepAdapter() {

        private val body = JPanel(BorderLayout())
        private val rootField = JBTextField(28)
        private val splitIntoModules = JBCheckBox().apply {
            // Registered once: the form is rebuilt whenever the plan changes, and a listener
            // added there would pile up a copy per rebuild.
            addActionListener { rootField.isEnabled = !isSelected }
        }
        private val keySeparatorField = JBTextField(6)
        private val nsSeparatorField = JBTextField(6)
        private val applyBoxes = mutableMapOf<WizardPlan.Field, JBCheckBox>()
        private val valueFields = mutableMapOf<WizardPlan.Field, JBTextField>()

        /** The plan the form currently shows; a rebuild would discard what the user typed. */
        private var shownPlan: WizardPlan.Plan? = null

        override fun getComponent(): JComponent = body
        override fun getPreferredFocusedComponent(): JComponent = rootField

        override fun _init() {
            val plan = WizardPlan.of(foundFiles, selectedFrameworks())
            if (plan == shownPlan) return
            shownPlan = plan
            rebuild(plan)
        }

        /**
         * Blocks *Apply* on the two values nothing else can repair.
         *
         * A blank separator is not a preference: it makes every composite key unresolvable, and
         * `ConfigDiagnostics` already reports it as a problem afterwards. Saying so here — where
         * the field is — is what the old wizard could not do, since it validated nothing at all.
         */
        override fun _commit(finishChosen: Boolean) {
            if (keySeparatorField.text.isBlank()) {
                throw CommitStepException(PluginBundle.message("wizard.summary.error.keySeparator"))
            }
            if (nsSeparatorField.text.isBlank()) {
                throw CommitStepException(PluginBundle.message("wizard.summary.error.nsSeparator"))
            }
        }

        private fun rebuild(plan: WizardPlan.Plan) {
            val settings = Settings.getInstance(project)
            val defaults = Config()

            rootField.text = plan.root.detected.orEmpty()
            // A previous plan may have left the field disabled behind a split that no longer
            // applies; the two must be reset together or the root becomes uneditable.
            rootField.isEnabled = true
            keySeparatorField.text = settings.keySeparator
            nsSeparatorField.text = settings.nsSeparator
            splitIntoModules.isSelected = false
            splitIntoModules.text = PluginBundle.message(
                "wizard.summary.root.split",
                plan.root.candidates.size,
                plan.root.candidates.joinToString(", ")
            )

            applyBoxes.clear()
            valueFields.clear()
            for (deduction in plan.deductions) {
                applyBoxes[deduction.field] = JBCheckBox(labelOf(deduction.field)).apply {
                    // Pre-ticked only while the setting still holds its default: the wizard can
                    // be reopened at any time, and it never overwrites a decision already made.
                    isSelected = isUntouched(deduction.field, settings, defaults)
                }
                if (deduction.value.isNotEmpty()) {
                    valueFields[deduction.field] = JBTextField(deduction.value, 16)
                }
            }

            body.removeAll()
            body.add(form(plan), BorderLayout.CENTER)
            body.revalidate()
            body.repaint()
        }

        private fun form(plan: WizardPlan.Plan): JComponent = panel {
            if (plan.root.widened) {
                row {
                    // The root is a guess here, not a reading: TranslationRootDetector widened to
                    // the longest common prefix because the catalogues disagreed. Announcing it
                    // like a fact is what made "Translations root: apps" look configured.
                    cell(
                        InlineBanner(
                            PluginBundle.message(
                                "wizard.summary.root.widened",
                                plan.root.detected.orEmpty(),
                                plan.root.candidates.joinToString(", ")
                            ),
                            EditorNotificationPanel.Status.Warning
                        )
                    ).align(AlignX.FILL)
                }
            }

            row(PluginBundle.message("wizard.summary.field.frameworks")) {
                label(
                    selectedFrameworks().joinToString(", ") { FrameworkDetector.LABELS[it] ?: it }
                        .ifEmpty { PluginBundle.message("wizard.summary.frameworks.none") }
                )
            }
            row(PluginBundle.message("wizard.summary.field.files")) {
                val count = label(PluginBundle.message("wizard.summary.files.count", foundFiles.size))
                if (foundFiles.isNotEmpty()) {
                    count.comment(foundFiles.take(SAMPLE_SIZE).joinToString(", "))
                }
            }

            row(PluginBundle.message("wizard.summary.field.root")) {
                cell(rootField).comment(
                    when {
                        plan.root.detected == null -> PluginBundle.message("wizard.summary.root.missing")
                        else -> PluginBundle.message(
                            "wizard.summary.origin.files",
                            plan.root.candidates.joinToString(", ")
                        )
                    }
                )
            }
            if (plan.root.widened) {
                row("") { cell(splitIntoModules) }
            }

            for (deduction in plan.deductions) {
                row("") {
                    cell(applyBoxes.getValue(deduction.field))
                    valueFields[deduction.field]?.let { cell(it) }
                }.rowComment(originOf(deduction))
            }

            row(PluginBundle.message("wizard.summary.field.keySeparator")) { cell(keySeparatorField) }
            row(PluginBundle.message("wizard.summary.field.nsSeparator")) { cell(nsSeparatorField) }

            if (foundFiles.any { it.endsWith(".po") || it.endsWith(".pot") }) {
                row {
                    cell(
                        InlineBanner(
                            PluginBundle.message("wizard.summary.gettext.warning"),
                            EditorNotificationPanel.Status.Info
                        )
                    ).align(AlignX.FILL)
                }
            }
        }.apply { border = JBUI.Borders.empty(16) }

        /** Where a value was read from, said in the user's own terms rather than as a rule. */
        private fun originOf(deduction: WizardPlan.Deduction): String = when {
            deduction.origin.isEmpty() -> ""
            deduction.field == WizardPlan.Field.FLAT_KEYS ->
                PluginBundle.message("wizard.summary.origin.framework", deduction.origin)
            else -> PluginBundle.message("wizard.summary.origin.file", deduction.origin)
        }

        private fun labelOf(field: WizardPlan.Field): String = when (field) {
            WizardPlan.Field.DEFAULT_NS -> PluginBundle.message("wizard.summary.field.defaultNs")
            WizardPlan.Field.GETTEXT -> PluginBundle.message("wizard.summary.field.gettext")
            WizardPlan.Field.FLAT_KEYS -> PluginBundle.message("wizard.summary.field.flatKeys")
            WizardPlan.Field.PREFERRED_LOCALIZATION -> PluginBundle.message("wizard.summary.field.preferredLocalization")
        }

        private fun isUntouched(field: WizardPlan.Field, settings: Settings, defaults: Config): Boolean = when (field) {
            WizardPlan.Field.DEFAULT_NS -> settings.defaultNs == defaults.defaultNs
            WizardPlan.Field.GETTEXT -> settings.gettext == defaults.gettext
            WizardPlan.Field.FLAT_KEYS -> settings.flatKeys == defaults.flatKeys
            WizardPlan.Field.PREFERRED_LOCALIZATION ->
                WizardSettingsDeducer.isUntouchedPreferredLocalization(settings.preferredLocalization)
        }

        /** What the form says should be stored — nothing is re-derived at this point. */
        fun choices(): WizardChoices {
            val plan = shownPlan ?: return WizardChoices()
            val splitting = plan.root.widened && splitIntoModules.isSelected
            return WizardChoices(
                translationsRoot = rootField.text.trim().takeUnless { splitting },
                modules = if (splitting) {
                    WizardPlan.modulesFor(plan.root, foundFiles, selectedFrameworks())
                } else {
                    emptyList()
                },
                defaultNs = chosenValue(WizardPlan.Field.DEFAULT_NS),
                gettext = true.takeIf { isTicked(WizardPlan.Field.GETTEXT) },
                flatKeys = true.takeIf { isTicked(WizardPlan.Field.FLAT_KEYS) },
                preferredLocalization = chosenValue(WizardPlan.Field.PREFERRED_LOCALIZATION),
                keySeparator = keySeparatorField.text,
                nsSeparator = nsSeparatorField.text
            )
        }

        private fun isTicked(field: WizardPlan.Field): Boolean = applyBoxes[field]?.isSelected == true

        private fun chosenValue(field: WizardPlan.Field): String? =
            valueFields[field]?.text?.trim()?.takeIf { isTicked(field) }
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
        for (relative in scanOffEdt(base)) {
            foundFiles.add(relative)
            fileListModel.addElement(relative)
        }
    }

    /**
     * Walks the project on a background thread, behind a cancellable progress.
     *
     * The walk used to run straight from `init`, on the EDT: on a large repository the window
     * froze before it ever appeared. `runProcessWithProgressSynchronously` is the form that
     * fits — the wizard needs the list before step 2 can be shown — and it keeps the walk off
     * the EDT while the modal progress stays responsive.
     *
     * Cancelling is a deliberate answer, not a failure: the dialog then opens on an empty list,
     * which is why the cancellation is caught here rather than propagated.
     */
    private fun scanOffEdt(base: File): List<String> =
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, RuntimeException>(
                { TranslationFileScanner.scan(base) { ProgressManager.checkCanceled() } },
                PluginBundle.message("wizard.scan.progress.title"),
                true,
                project
            ) ?: emptyList()
        } catch (canceled: ProcessCanceledException) {
            emptyList()
        }

    /**
     * Stores what the summary form holds — not what the scan implies.
     *
     * The deduction still decides what is *offered*; the user decides what is *kept*. Writing
     * the deduction again here would quietly undo every correction made on the last step.
     */
    override fun doOKAction() {
        summaryStep.choices().applyTo(Settings.getInstance(project))
        super.doOKAction()
    }

    private fun selectedFrameworks(): Set<String> =
        frameworkCheckboxes.filterValues { it.isSelected }.keys

    // No doCancelAction override: skipping the wizard says nothing about wanting it gone. Only
    // "Don't show again", and the settings checkbox behind it, switch the suggestion off.

    /**
     * The step rail: every step named at once, the current one picked out.
     *
     * "Step 2 of 3" told the user where they were and nothing about where they were going.
     */
    private class StepRail(private val titles: List<String>) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

        private val labels = titles.mapIndexed { index, title ->
            JBLabel("${index + 1}. $title").apply { border = JBUI.Borders.empty(8, 12) }
        }

        init {
            border = JBUI.Borders.emptyLeft(4)
            labels.forEach { add(it) }
            select(0)
        }

        fun select(index: Int) {
            labels.forEachIndexed { position, label ->
                label.foreground = if (position == index) ACTIVE else INACTIVE
                label.font = label.font.deriveFont(if (position == index) 1 else 0)
            }
        }

        private companion object {
            // From the IDE scheme rather than hand-picked RGB, as the stats bar does since #209.
            val ACTIVE = JBColor.namedColor("Label.foreground", 0x000000, 0xBBBBBB)
            val INACTIVE = JBColor.namedColor("Label.disabledForeground", 0x8C8C8C, 0x777777)
        }
    }

    private companion object {
        /** How many of the found files the summary names before falling back to the count. */
        const val SAMPLE_SIZE = 5
    }
}
