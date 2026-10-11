package com.ibrahimdans.i18n.extensions.localization.php

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * The PHP half of [com.ibrahimdans.i18n.plugin.utils.TranslationPsi], loaded only once an element
 * is known to be PHP: the PHP plugin is an optional dependency, and naming one of its classes from
 * the shared code would fail on a JSON file without it.
 */
internal object PhpTranslationPsi {

    /**
     * The text of the string literal [element], `\'` and `\\` read back as the characters they
     * escape in a single-quoted string; a double-quoted string's contents as written. Null for any
     * other value.
     */
    fun readValue(element: PsiElement): String? {
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

    /** Replaces the string literal [element] with [newValue], single-quoted; any other value is left. */
    fun replaceValue(element: PsiElement, newValue: String, project: Project) {
        if (element !is StringLiteralExpression) return
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("value.php", PhpFileType.INSTANCE, "<?php\nreturn ${PhpArrayContentGenerator.quote(newValue)};\n")
        val literal = PsiTreeUtil.findChildOfType(file, StringLiteralExpression::class.java) ?: return
        element.replace(literal)
    }
}
