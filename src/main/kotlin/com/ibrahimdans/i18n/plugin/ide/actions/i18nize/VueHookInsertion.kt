package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.extensions.lang.js.VueScriptTextExtractor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * The `const { t } = useI18n()` a `<script setup>` needs once its strings call `t`, and the
 * `useI18n` import — merged into an existing `vue-i18n` import when there is one — as [HookInsertion]
 * does for React components.
 *
 * Read-only: it computes [CodeEdit]s, the caller applies them in one command. A script declaring
 * a `t` at its top level gets nothing.
 */
internal object VueHookInsertion {

    const val HOOK = "useI18n"
    const val MODULE = "vue-i18n"

    /** The edits giving each `<script setup>` holding one of [literals] its `t`. */
    fun edits(literals: List<PsiElement>): List<CodeEdit> =
        literals.filter { VueScriptTextExtractor.styleOf(it) == VueScriptTextExtractor.Style.SETUP }
            .mapNotNull { PsiTreeUtil.getParentOfType(it, JSEmbeddedContent::class.java) }
            .distinct()
            .filterNot(::declaresT)
            .flatMap(::scriptEdits)

    private fun scriptEdits(script: JSEmbeddedContent): List<CodeEdit> {
        val imports = PsiTreeUtil.getChildrenOfTypeAsList(script, ES6ImportDeclaration::class.java)
        val last = imports.lastOrNull()
        val end = if (last?.text?.trimEnd()?.endsWith(";") == true) ";" else ""
        val hook = "const { t } = $HOOK()$end"
        val existing = imports.firstOrNull { it.fromClause?.referenceText?.trim('\'', '"', '`') == MODULE && !it.text.startsWith("import type") }
        val specifiers = existing?.importSpecifiers.orEmpty()
        val imported = specifiers.any { it.text.trim() == HOOK }
        val importEdit = when {
            imported || existing == null -> null
            specifiers.isNotEmpty() -> CodeEdit(TextRange.from(specifiers.first().textRange.startOffset, 0), "$HOOK, ")
            existing.namedImports != null -> CodeEdit(TextRange.from(existing.namedImports!!.textRange.startOffset + 1, 0), " $HOOK ")
            else -> null
        }
        val newImport = if (imported || importEdit != null) "" else "\nimport { $HOOK } from '$MODULE'$end"
        val at = last?.textRange?.endOffset ?: script.textRange.startOffset
        val after = if (last == null) "\n" else ""
        return listOfNotNull(importEdit, CodeEdit(TextRange.from(at, 0), "$newImport\n$hook$after"))
    }

    /** Whether [script] declares a `t` outside any function: its strings call it already. */
    private fun declaresT(script: JSEmbeddedContent): Boolean =
        PsiTreeUtil.findChildrenOfType(script, JSVariable::class.java)
            .any { it.name == "t" && PsiTreeUtil.getParentOfType(it, JSFunction::class.java, true, JSEmbeddedContent::class.java) == null }
}
