package com.ibrahimdans.i18n.extensions.lang.js

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Adds named imports to a JavaScript or TypeScript file: `useTranslation` from `react-i18next` for
 * the hook *Internationalize…* inserts, `FormattedMessage` and `useIntl` from `react-intl` for what
 * an extraction writes.
 */
internal object EsImports {

    /** Text to insert at an offset of the file. */
    data class Insertion(val offset: Int, val text: String)

    /**
     * [names] added to [file]'s import of [module] — merged into an existing one, else a new import
     * after the last one or after the directives (`'use client'`), which must stay first. Null when
     * every name is imported already.
     */
    fun insertion(file: PsiFile, module: String, names: List<String>): Insertion? {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        val existing = imports.filter { it.fromClause?.referenceText?.trim('\'', '"', '`') == module && !it.text.startsWith("import type") }
        val imported = existing.flatMap { declaration -> declaration.importSpecifiers.map { it.text.trim() } }.toSet()
        val missing = names.filterNot { it in imported }
        if (missing.isEmpty()) return null
        val list = missing.joinToString(", ")
        for (declaration in existing) {
            val named = declaration.namedImports
            val specifiers = declaration.importSpecifiers
            when {
                // Before the first one: valid whatever follows the last (a trailing comma).
                specifiers.isNotEmpty() -> return Insertion(specifiers.first().textRange.startOffset, "$list, ")
                named != null -> return Insertion(named.textRange.startOffset + 1, " $list ")
                declaration.text.contains('*') -> continue
                declaration.importedBindings.isNotEmpty() ->
                    return Insertion(declaration.importedBindings.last().textRange.endOffset, ", { $list }")
            }
        }
        val statement = "import { $list } from '$module';"
        imports.lastOrNull()?.let { return Insertion(it.textRange.endOffset, "\n$statement") }
        val directive = file.children.asSequence()
            .filterNot { it is PsiWhiteSpace || it is PsiComment }
            .takeWhile { ((it as? JSExpressionStatement)?.expression as? JSLiteralExpression)?.isQuotedLiteral == true }
            .lastOrNull()
        return if (directive != null) Insertion(directive.textRange.endOffset, "\n$statement")
        else Insertion(0, "$statement\n")
    }
}
