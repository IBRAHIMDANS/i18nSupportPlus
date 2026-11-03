package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.TranslationValues
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * The PHP half of [com.ibrahimdans.i18n.plugin.utils.TranslationPsi], reached through
 * [PhpArrayLocalization.translationValues] only: the PHP plugin is an optional dependency, and the
 * shared code must not name this class — the Marketplace verifier reports every PHP class it
 * reaches from there as missing on the IDEs without PHP, and blocks the release.
 */
internal object PhpTranslationPsi : TranslationValues {

    /** The id of the PHP language — registered by the PHP plugin only. */
    private const val PHP_LANGUAGE = "PHP"

    override fun handles(element: PsiElement): Boolean = element.language.id == PHP_LANGUAGE

    /**
     * The text of the string literal [element], `\'` and `\\` read back as the characters they
     * escape in a single-quoted string; a double-quoted string's contents as written. Null for any
     * other value.
     */
    override fun readValue(element: PsiElement): String? {
        val literal = element as? StringLiteralExpression ?: return null
        if (!literal.isSingleQuote) return literal.contents
        val contents = literal.contents
        return buildString {
            var i = 0
            while (i < contents.length) {
                val c = contents[i]
                val next = contents.getOrNull(i + 1)
                if (c == '\\' && (next == '\'' || next == '\\')) {
                    append(next)
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }

    /**
     * The key literal of the translation entry whose value is [value] — `'failed'` in
     * `'auth' => ['failed' => '…']` — or null when [value] is no value of a translation file's array.
     */
    override fun keyOf(value: PsiElement): StringLiteralExpression? {
        val entry = PsiTreeUtil.getParentOfType(value, ArrayHashElement::class.java) ?: return null
        if (PhpArrays.valueOf(entry) != value) return null
        return PhpArrays.keyOf(entry)?.takeIf { PhpArrays.pathOf(it) != null }
    }

    /** Replaces the string literal [element] with [newValue], single-quoted; any other value is left. */
    override fun replaceValue(element: PsiElement, newValue: String, project: Project) {
        if (element !is StringLiteralExpression) return
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("value.php", PhpFileType.INSTANCE, "<?php\nreturn ${PhpArrayContentGenerator.quote(newValue)};\n")
        val literal = PsiTreeUtil.findChildOfType(file, StringLiteralExpression::class.java) ?: return
        element.replace(literal)
    }
}
