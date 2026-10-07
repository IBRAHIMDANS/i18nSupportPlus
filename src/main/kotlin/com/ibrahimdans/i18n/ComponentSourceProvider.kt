package com.ibrahimdans.i18n

import com.intellij.psi.PsiElement

/**
 * Translations a component declares for itself, next to its code: vue-i18n's `<i18n>` block of a
 * single-file component. They take precedence over the project's translation files for the keys
 * written in that component, as the framework resolves them.
 *
 * An extension point rather than code of the core: the framework's PSI belongs to an optional
 * plugin (Vue), declared in its own config file.
 */
interface ComponentSourceProvider {

    /** The sources the component holding [caller] declares, the first locale first; none outside one. */
    fun sourcesFor(caller: PsiElement): List<LocalizationSource>
}
