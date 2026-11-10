package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.Lang
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement

/**
 * Annotator for i18n keys: colours the keys of [lang] that resolve and, when [reportsProblems],
 * reports the ones that do not. A language whose problems
 * [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspectionBase] reports passes false:
 * its keys keep their colour here, and their problems get a severity, a suppression comment and a
 * place in *Inspect Code* there.
 */
abstract class CompositeKeyAnnotatorBase(lang: Lang, private val reportsProblems: Boolean = true) : Annotator {

    private val check = KeyCheck(lang)

    /**
     * Tries to parse element as i18n key and annotates it when succeeded
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        check.check(element, AnnotationReport(holder, reportsProblems))
    }
}
