package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.Lang
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement

/**
 * Annotator for i18n keys: colours the keys of [lang] that resolve. The ones that do not are
 * reported by [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection], where they get
 * a severity, a suppression comment and a place in *Inspect Code*.
 */
abstract class CompositeKeyAnnotatorBase(lang: Lang) : Annotator {

    private val check = KeyCheck(lang)

    /**
     * Tries to parse element as i18n key and annotates it when succeeded
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        check.check(element, AnnotationReport(holder))
    }
}
