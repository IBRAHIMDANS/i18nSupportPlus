package com.ibrahimdans.i18n.plugin.ide.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange

/**
 * Where the outcome of [KeyCheck] goes. The annotator colours the keys that resolve; the
 * inspection reports the ones that do not, so that their severity can be set in the profile, a
 * comment suppresses them, and *Inspect Code* or Qodana lists them.
 */
interface KeyReport {

    /** The key on [range] resolves. */
    fun resolved(range: TextRange)

    /**
     * A problem of the key on [range]: [message], and the HTML [tooltip] explaining it when there is
     * one — asked only by a report that shows the problem, since it looks the key up again. [weak]
     * for a key that is still to create while the call shows a default value meanwhile.
     */
    fun problem(range: TextRange, message: String, weak: Boolean, tooltip: (() -> String)?, fixes: List<IntentionAction>)
}

/**
 * The annotator's report: resolved keys coloured as comments. [reportsProblems] is false for a
 * language whose problems [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspectionBase]
 * reports: the annotator reporting them as well would show each problem twice.
 */
class AnnotationReport(private val holder: AnnotationHolder, private val reportsProblems: Boolean) : KeyReport {

    override fun resolved(range: TextRange) {
        holder
            .newAnnotation(HighlightSeverity.INFORMATION, "")
            .range(range)
            .textAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
            .create()
    }

    override fun problem(range: TextRange, message: String, weak: Boolean, tooltip: (() -> String)?, fixes: List<IntentionAction>) {
        if (!reportsProblems) return
        var builder = holder
            .newAnnotation(if (weak) HighlightSeverity.WEAK_WARNING else HighlightSeverity.ERROR, message)
            .range(range)
        if (tooltip != null) builder = builder.tooltip(tooltip())
        fixes.forEach { builder = builder.withFix(it) }
        builder.create()
    }
}
