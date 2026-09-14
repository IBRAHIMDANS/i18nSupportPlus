package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.openapi.fileTypes.FileType

/**
 * Describes localization source.
 * May be root of json, yaml file, js object
 *
 * [locale] and [namespace] are set when something states them — a module's path template — and
 * take precedence over the guess made from [name] and [parent]. A [locale] with no [namespace] is a
 * file named after its locale, holding the default namespace.
 */
data class LocalizationSource(
    val tree: Tree<PsiElement>?,
    val name: String,
    val parent: String,
    val displayPath: String,
    val localization: Localization<PsiElement>,
    val host: PsiElement? = null,
    val locale: String? = null,
    val namespace: String? = null
)
