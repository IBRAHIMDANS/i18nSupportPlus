package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a value identical to the reference locale's: `fr.json: "title": "Account settings"` is
 * counted as translated by the table and the statistics, while it is typically a key created by
 * copying the donor locale and never taken up again.
 *
 * Only a value of more than one word is reported: `OK`, `Email` or a brand name are the same in
 * many languages. A value meant to stay as is goes to [ignoredValues], by hand in the inspection
 * options or through the *Mark as intended* quick fix.
 */
class UntranslatedValueInspection : LocalInspectionTool() {

    /** Values that are the same in the reference on purpose, compared trimmed. */
    @JvmField
    var ignoredValues: MutableList<String> = mutableListOf()

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = SHORT_NAME

    override fun getOptionsPane(): OptPane =
        OptPane.pane(OptPane.stringList("ignoredValues", PluginBundle.message("inspection.untranslated.option.ignored")))

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        referenceValueVisitor(holder) { literal, value, reference ->
            val text = value.trim()
            if (text != reference.trim() || !isSentence(text) || text in ignoredValues) return@referenceValueVisitor
            holder.registerProblem(literal, PluginBundle.message("inspection.untranslated.message"), MarkAsIntendedFix(text))
        }

    internal companion object {

        const val SHORT_NAME = "I18nUntranslatedValue"

        private val WHITESPACE = Regex("\\s+")

        /** More than one word holding a letter: what a translator would have changed. */
        fun isSentence(text: String): Boolean =
            text.split(WHITESPACE).count { word -> word.any { it.isLetter() } } > 1
    }
}

/** Adds the value to [UntranslatedValueInspection.ignoredValues] of the project's inspection profile. */
private class MarkAsIntendedFix(private val value: String) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.untranslated.fix.name")
    override fun getFamilyName(): String = name
    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    /**
     * Changes the tool of the profile in use and tells its manager, which saves it and restarts
     * the highlighting — the profile's own tool, so the value is ignored at once.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val manager = ProjectInspectionProfileManager.getInstance(project)
        val profile = manager.currentProfile
        val tool = profile.getUnwrappedTool(UntranslatedValueInspection.SHORT_NAME, file) as? UntranslatedValueInspection ?: return
        if (value in tool.ignoredValues) return
        tool.ignoredValues.add(value)
        profile.profileChanged()
        manager.fireProfileChanged(profile)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
