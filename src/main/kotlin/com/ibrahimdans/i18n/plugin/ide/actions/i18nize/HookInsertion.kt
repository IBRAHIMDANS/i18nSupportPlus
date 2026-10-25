package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.extensions.lang.js.EsImports
import com.ibrahimdans.i18n.extensions.lang.js.ReactIntlCall
import com.ibrahimdans.i18n.extensions.lang.js.TranslationHookInScope
import com.ibrahimdans.i18n.extensions.lang.js.formatMessageInScope
import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
 * In react-intl, whose strings become `intl.formatMessage(…)`, the component gets
 * `const intl = useIntl()` and the `useIntl` import from `react-intl` instead.
 *
 * Read-only: it computes [CodeEdit]s on the file as it is, the caller applies them in one command.
 * A component already declaring a `t`, or calling a translation hook (even with a `keyPrefix`,
 * whose `t` the extraction does not use), never gets a second one — nor one declaring an `intl`.
 */
internal object HookInsertion {

    const val HOOK = "useTranslation"
    const val MODULE = "react-i18next"

    private val HOOKS = setOf(HOOK, "useI18n")
    private const val GLOBAL_CALL = "i18n.t("

    const val USE_INTL = "useIntl"
    private const val INTL = "intl"

    /**
     * The React component [element] is written in when it should receive a hook: no hook in scope,
     * no call template in its module (the module writes its own call), a function named with a capital
     * — `function Profile()`, `const Profile = () =>` — holding JSX, and no `t` of its own. Else null.
     */
    fun componentNeedingHook(element: PsiElement): JSFunction? {
        if (CallTemplate.of(element) != null || TranslationHookInScope.find(element) != null) return null
        // react-intl has no `t`: its component needs `useIntl()`, see [componentNeedingIntl].
        if (ReactIntlCall.applies(element)) return null
        return reactComponentOf(element)?.takeUnless(::hasOwnTranslation)
    }

    /**
     * The React component a react-intl string at [element] is written in when it should receive
     * `const intl = useIntl()`: the extraction writes `intl.formatMessage(…)` there, and no `useIntl()`
     * is in scope. Else null — also for a component that declares an `intl` of its own.
     */
    fun componentNeedingIntl(element: PsiElement): JSFunction? {
        if (!ReactIntlCall.applies(element) || formatMessageInScope(element) != null) return null
        return reactComponentOf(element)?.takeUnless(::declaresIntl)
    }

    /** [written], a call an extraction wrote with the global `i18n.t`, calling the hook's `t` instead. */
    fun callThroughHook(written: String): String = written.replaceFirst(GLOBAL_CALL, "t(")

    /** The edits giving each of [components] its hook, in the namespace given, and [file] the import. */
    fun edits(file: PsiFile, components: Map<JSFunction, String>): List<CodeEdit> {
        if (components.isEmpty()) return emptyList()
        return components.mapNotNull { (component, namespace) ->
            statementEdits(component, "const { t } = $HOOK('${namespace.replace("'", "\\'")}');")
        }.flatten() + listOfNotNull(importEdit(file, MODULE, listOf(HOOK)))
    }

    /**
     * The edits giving each of [components] its `const intl = useIntl();`, and [file] the imports of
     * [imports] from `react-intl` — `FormattedMessage` for the texts that became elements, `useIntl` as
     * soon as a component gets the hook.
     */
    fun intlEdits(file: PsiFile, components: Set<JSFunction>, imports: Set<String>): List<CodeEdit> {
        val names = (imports + listOfNotNull(USE_INTL.takeIf { components.isNotEmpty() })).sorted()
        return components.mapNotNull { statementEdits(it, "const $INTL = $USE_INTL();") }.flatten() +
            listOfNotNull(importEdit(file, ReactIntlCall.MODULE, names))
    }

    /** [statement] at the top of [component]'s body. */
    private fun statementEdits(component: JSFunction, hook: String): List<CodeEdit>? {
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

    /** [names] added to [file]'s import of [module], or a new import; null when all are imported. */
    private fun importEdit(file: PsiFile, module: String, names: List<String>): CodeEdit? =
        EsImports.insertion(file, module, names)?.let { CodeEdit(TextRange.from(it.offset, 0), it.text) }

    /** The component around [element]: a function named with a capital, holding JSX. */
    private fun reactComponentOf(element: PsiElement): JSFunction? =
        componentOf(element)?.takeIf { PsiTreeUtil.findChildOfType(it, XmlTag::class.java) != null }

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

    /** Whether [component] declares an `intl` (a variable, a parameter) of its own. */
    private fun declaresIntl(component: JSFunction): Boolean =
        PsiTreeUtil.findChildrenOfType(component, JSVariable::class.java)
            .any { it.name == INTL && PsiTreeUtil.getParentOfType(it, JSFunction::class.java) == component }
}
