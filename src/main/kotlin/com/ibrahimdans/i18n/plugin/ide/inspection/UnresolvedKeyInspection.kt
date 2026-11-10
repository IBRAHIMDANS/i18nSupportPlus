package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.annotator.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.annotator.KeyReport
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.IntentionWrapper
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports a key of the code no translation file resolves: an unresolved key or namespace, a
 * missing default translation file, a key pointing at an object, a partially translated key. The
 * same check, ranges, messages, tooltip and quick fixes as the annotators had, which now only
 * colour the keys that resolve.
 *
 * Being an inspection is the point: its severity is set in the profile, `// noinspection` (or the
 * suppress fix) silences one call, and *Inspect Code*, `inspect.sh` and Qodana list the keys.
 *
 * One inspection for every language, declared in `plugin.xml`: each element is read by the
 * language a [com.ibrahimdans.i18n.Lang] extension names for it ([Lang.inspectionLangOf]), so
 * nothing loaded here names the PSI of an optional plugin — JavaScript, PHP, Twig — and an IDE
 * without one of them still loads it.
 */
class UnresolvedKeyInspection : LocalInspectionTool() {

    private val checks = ConcurrentHashMap<Lang, KeyCheck>()

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = SHORT_NAME

    /**
     * The check reading the keys of [element], or null when no language reads it. Asked per
     * element, not per file: a Svelte, Vue or Blade file holds elements of several languages in
     * one tree.
     */
    private fun checkOf(element: PsiElement): KeyCheck? {
        val lang = Extensions.LANG.extensionList.firstNotNullOfOrNull { it.inspectionLangOf(element) } ?: return null
        return checks.computeIfAbsent(lang, ::KeyCheck)
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                checkOf(element)?.check(element, ProblemReport(holder, element, isOnTheFly))
            }
        }
    }

    companion object {
        const val SHORT_NAME = "I18nUnresolvedKey"
    }
}

/**
 * The inspection's report: each problem a [ProblemDescriptorBase] on [element], its range taken
 * back to the element's own coordinates, the HTML tooltip kept apart from the plain description
 * (the description is what *Inspect Code* lists).
 *
 * The fixes are offered in the editor only. They are the annotator's intention actions, which open
 * a dialog or a popup on the editor of the file: applied from the *Inspect Code* results, with no
 * editor to show them on, they would have nothing to open.
 */
private class ProblemReport(
    private val holder: ProblemsHolder,
    private val element: PsiElement,
    private val isOnTheFly: Boolean,
) : KeyReport {

    override fun resolved(range: TextRange) = Unit

    override fun problem(range: TextRange, message: String, weak: Boolean, tooltip: (() -> String)?, fixes: List<IntentionAction>) {
        val inElement = range.shiftLeft(element.textRange.startOffset)
        if (inElement.startOffset < 0 || inElement.endOffset > element.textLength) return
        val quickFixes = if (isOnTheFly) IntentionWrapper.wrapToQuickFixes(fixes, holder.file).toTypedArray() else LocalQuickFix.EMPTY_ARRAY
        val highlight = if (weak) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        // The tooltip looks the key up again: only the editor shows it.
        val explanation = if (isOnTheFly) tooltip?.invoke() else null
        holder.registerProblem(ExplainedProblem(element, message, quickFixes, highlight, inElement, isOnTheFly, explanation))
    }
}

/** A problem whose tooltip says more than its description: how the key was read and where it was looked up. */
private class ExplainedProblem(
    element: PsiElement,
    message: String,
    fixes: Array<LocalQuickFix>,
    highlight: ProblemHighlightType,
    range: TextRange,
    onTheFly: Boolean,
    private val tooltip: String?,
) : ProblemDescriptorBase(element, element, message, fixes, highlight, false, range, true, onTheFly) {

    override fun getTooltipTemplate(): String = tooltip ?: descriptionTemplate
}
