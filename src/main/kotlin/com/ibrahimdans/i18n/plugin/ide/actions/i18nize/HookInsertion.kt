package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.extensions.lang.js.TranslationHookInScope
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/** Replaces [range] of a document by [text]; an empty [range] inserts. */
internal data class CodeEdit(val range: TextRange, val text: String)

/**
 * The `const { t } = useTranslation('ns')` a React component needs once its texts call `t`.
 *
 * An extraction in a component without a hook writes `i18n.t('key')`, which the component does not
 * import: *Internationalize…* gives the component the hook instead, at the top of its body, and the
 * `useTranslation` import — merged into an existing `react-i18next` import when there is one.
 *
 * Read-only: it computes [CodeEdit]s on the file as it is, the caller applies them in one command.
 * A component already declaring a `t`, or calling a translation hook (even with a `keyPrefix`,
 * whose `t` the extraction does not use), never gets a second one.
 */
internal object HookInsertion {

    const val HOOK = "useTranslation"
    const val MODULE = "react-i18next"

    private val HOOKS = setOf(HOOK, "useI18n")
    private const val GLOBAL_CALL = "i18n.t("

    /**
     * The React component [element] is written in when it should receive a hook: no hook in scope,
     * no call template in its module (the module writes its own call), a function named with a capital
     * — `function Profile()`, `const Profile = () =>` — holding JSX, and no `t` of its own. Else null.
     */
    fun componentNeedingHook(element: PsiElement): JSFunction? {
        if (CallTemplate.of(element) != null || TranslationHookInScope.find(element) != null) return null
        val component = componentOf(element) ?: return null
        if (PsiTreeUtil.findChildOfType(component, XmlTag::class.java) == null) return null
        return component.takeUnless(::hasOwnTranslation)
    }

    /** [written], a call an extraction wrote with the global `i18n.t`, calling the hook's `t` instead. */
    fun callThroughHook(written: String): String = written.replaceFirst(GLOBAL_CALL, "t(")

    /** The edits giving each of [components] its hook, in the namespace given, and [file] the import. */
    fun edits(file: PsiFile, components: Map<JSFunction, String>): List<CodeEdit> {
        if (components.isEmpty()) return emptyList()
        return components.mapNotNull { (component, namespace) -> hookEdits(component, namespace) }.flatten() +
            listOfNotNull(importEdit(file))
    }

    private fun hookEdits(component: JSFunction, namespace: String): List<CodeEdit>? {
        val hook = "const { t } = $HOOK('${namespace.replace("'", "\\'")}');"
        val block = component.block
        if (block == null) {
            // `const Menu = () => <nav>…</nav>`: the body becomes a block returning the expression.
            val body = component.children.filterIsInstance<JSExpression>().lastOrNull() ?: return null
            return listOf(
                CodeEdit(TextRange.from(body.textRange.startOffset, 0), "{ $hook return "),
                CodeEdit(TextRange.from(body.textRange.endOffset, 0), "; }")
            )
        }
        val first = block.statementListItems.firstOrNull()
        if (first == null) {
            return listOf(CodeEdit(TextRange.from(block.textRange.startOffset + 1, 0), " $hook "))
        }
        val text = block.containingFile.text
        val lineStart = text.lastIndexOf('\n', first.textRange.startOffset - 1) + 1
        val separator = if (lineStart > block.textRange.startOffset) "\n" + text.substring(lineStart, first.textRange.startOffset) else " "
        return listOf(CodeEdit(TextRange.from(first.textRange.startOffset, 0), hook + separator))
    }

    /** `useTranslation` added to the file's `react-i18next` import, or a new import; null when already imported. */
    private fun importEdit(file: PsiFile): CodeEdit? {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        val existing = imports.filter { it.fromClause?.referenceText?.trim('\'', '"', '`') == MODULE && !it.text.startsWith("import type") }
        if (existing.any { declaration -> declaration.importSpecifiers.any { it.text.trim() == HOOK } }) return null
        for (declaration in existing) {
            val named = declaration.namedImports
            val specifiers = declaration.importSpecifiers
            when {
                // Before the first one: valid whatever follows the last (a trailing comma).
                specifiers.isNotEmpty() -> return CodeEdit(TextRange.from(specifiers.first().textRange.startOffset, 0), "$HOOK, ")
                named != null -> return CodeEdit(TextRange.from(named.textRange.startOffset + 1, 0), " $HOOK ")
                declaration.text.contains('*') -> continue
                declaration.importedBindings.isNotEmpty() ->
                    return CodeEdit(TextRange.from(declaration.importedBindings.last().textRange.endOffset, 0), ", { $HOOK }")
            }
        }
        val statement = "import { $HOOK } from '$MODULE';"
        imports.lastOrNull()?.let { return CodeEdit(TextRange.from(it.textRange.endOffset, 0), "\n$statement") }
        // After the directives (`'use client'`), which must stay first.
        val directive = file.children.asSequence()
            .filterNot { it is PsiWhiteSpace || it is PsiComment }
            .takeWhile { ((it as? JSExpressionStatement)?.expression as? JSLiteralExpression)?.isQuotedLiteral == true }
            .lastOrNull()
        return if (directive != null) CodeEdit(TextRange.from(directive.textRange.endOffset, 0), "\n$statement")
        else CodeEdit(TextRange.from(0, 0), "$statement\n")
    }

    /** The innermost function named with a capital around [element]; null inside a class first. */
    private fun componentOf(element: PsiElement): JSFunction? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is JSClass) return null
            if (current is JSFunction) {
                val name = current.name ?: PsiTreeUtil.getParentOfType(current, JSVariable::class.java, true, JSFunction::class.java)?.name
                if (name != null && name.first().isUpperCase()) return current
            }
            current = current.parent
        }
        return null
    }

    /** Whether [component] declares a `t` (a variable, a parameter) or calls a translation hook itself. */
    private fun hasOwnTranslation(component: JSFunction): Boolean {
        fun ownedBy(element: PsiElement) = PsiTreeUtil.getParentOfType(element, JSFunction::class.java) == component
        val declaresT = PsiTreeUtil.findChildrenOfType(component, JSVariable::class.java).any { it.name == "t" && ownedBy(it) }
        val callsHook = PsiTreeUtil.findChildrenOfType(component, JSCallExpression::class.java)
            .any { it.methodExpression?.text in HOOKS && ownedBy(it) }
        return declaresT || callsHook
    }
}
