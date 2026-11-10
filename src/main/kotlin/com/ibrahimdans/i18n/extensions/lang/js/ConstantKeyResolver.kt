package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactUseTranslationHookExtractor
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nGotoDeclarationHandler
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.references.code.ReferenceDescriptor
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.rules.RuleDecision
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptAsExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Keys a call names through a constant: `t(SAVE_KEY)`, `t(KEYS.save)`, `t(Keys.Save)`.
 *
 * One hop, and one only: the argument resolves to a `const` (or a `let` never reassigned) whose
 * initializer is a string — a literal, a template without expression, or a conditional whose every
 * branch is one —, to a property of an object literal initializing a `const` (`as const` or not),
 * or to a string member of an `enum`. A function, a parameter, a property of a parameter, a
 * variable initialized from another one: nothing, as before.
 *
 * Two places read the key:
 *  - the call, when the constant holds one string: annotated, hinted and Ctrl+clicked there
 *    ([callKey], [ConstantKeyGotoDeclarationHandler]);
 *  - the declaration's literal, whatever the constant holds: it carries the reference that *Find
 *    Usages* and Shift+F6 follow ([declarationKey]), so a rename rewrites it once, not once per call.
 *    A conditional's branches are annotated there too, the call standing for several keys at once —
 *    only for a local constant, whose calls a search of its own scope finds.
 *
 * The namespace is the call's: the hook declaring `t`, or its `{ ns }` option.
 */
internal object ConstantKeyResolver {

    /** Barrels re-exporting barrels: deep enough for real projects, bounded against a cycle. */
    private const val MAX_RE_EXPORTS = 8

    private val HOOK = ReactUseTranslationHookExtractor()

    private fun translationFunctionNames(): List<String> =
        Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }

    /**
     * True when [element] is a reference passed first to a translation call: `KEYS.save` in
     * `t(KEYS.save)`, matched the way [JsLang] matches a literal there.
     */
    fun isKeyArgument(element: PsiElement, names: List<String> = translationFunctionNames()): Boolean {
        if (element !is JSReferenceExpression) return false
        val arguments = element.parent as? JSArgumentList ?: return false
        if (arguments.arguments.firstOrNull() !== element) return false
        val method = (arguments.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
        when (jsRuleDecision(element)) {
            RuleDecision.INCLUDE -> return true
            RuleDecision.EXCLUDE -> return false
            RuleDecision.NONE -> {}
        }
        val presetNames = ModulePresets.restrict(names, ModulePresets.presetOf(element))
        return method.referenceName in presetNames.map { it.substringAfterLast('.') } &&
            isDirectOrConfiguredCall(element, presetNames)
    }

    /** The key the constant passed as [argument] holds, or null when it is no constant holding exactly one. */
    fun callKey(argument: PsiElement): RawKey? {
        if (!isKeyArgument(argument)) return null
        val literal = literalsOf(argument as JSReferenceExpression).singleOrNull() ?: return null
        return keyAt(argument, literal)
    }

    /**
     * The key [literal] holds when it initializes a constant a translation call is passed, or null.
     * With [annotatedOnly], only a branch of a local constant's conditional: the one place the call
     * cannot stand for its key.
     */
    fun declarationKey(literal: PsiElement, annotatedOnly: Boolean = false): RawKey? =
        (literal as? JSLiteralExpression)?.let { declarationArgument(it, annotatedOnly)?.let { argument -> keyAt(argument, it) } }

    /** The first translation call argument naming the constant [literal] initializes, or null. */
    fun declarationArgument(literal: PsiElement, annotatedOnly: Boolean = false): PsiElement? {
        val declared = declaredBy(literal) ?: return null
        if (annotatedOnly && !(declared.branch && declared.owner.useScope is LocalSearchScope)) return null
        return usingArgument(declared.owner)
    }

    /** [literal]'s value read at [argument]'s call: its hook's namespace and key prefix, its options. */
    private fun keyAt(argument: PsiElement, literal: JSLiteralExpression): RawKey? {
        val value = valueOf(literal) ?: return null
        return HOOK.extract(argument).copy(keyElements = listOf(KeyElement.literal(value)))
    }

    /** The string literals held by the constant [argument] resolves to; empty for anything else. */
    private fun literalsOf(argument: JSReferenceExpression): List<JSLiteralExpression> {
        val target = argument.resolve()?.let(::declarationOf) ?: return emptyList()
        val initializer = when (target) {
            is TypeScriptEnumField -> target.initializer
            is JSParameter -> null
            is JSVariable -> target.initializer
            is JSProperty -> target.value
            else -> null
        }
        return literalsIn(initializer).orEmpty().filter { declaredBy(it)?.owner == target && constant(target) }
    }

    /**
     * The declaration [target] stands for: the one an import names (`import { KEYS } from './keys'`),
     * and the property behind the implicit element TypeScript resolves `KEYS.save` to on an `as const` object.
     */
    private fun declarationOf(target: PsiElement): PsiElement? {
        val declared = if (target is ES6ImportSpecifier) importedDeclaration(target) else target
        return if (declared is JSImplicitElement) declared.navigationElement else declared
    }

    /**
     * The declaration an import names, followed through re-exports (`export { KEYS } from './keys'`).
     * `multiResolve` is the platform's reference API: `resolveOverAliases` is gone from the JavaScript plugin in 2026.1.
     */
    private fun importedDeclaration(specifier: ES6ImportExportSpecifier): PsiElement? {
        var current: PsiElement = specifier
        repeat(MAX_RE_EXPORTS) {
            val next = (current as? ES6ImportExportSpecifier)?.multiResolve(false)?.firstNotNullOfOrNull { it.element }
            if (next == null || next == current) return if (current == specifier) null else current
            current = next
        }
        return current
    }

    /** The string literals [expression] is made of, or null when one of its branches is something else. */
    private fun literalsIn(expression: JSExpression?): List<JSLiteralExpression>? = when (expression) {
        is JSParenthesizedExpression -> literalsIn(expression.innerExpression)
        is TypeScriptAsExpression -> literalsIn(expression.expression)
        is JSConditionalExpression -> {
            val then = literalsIn(expression.thenBranch)
            val otherwise = literalsIn(expression.elseBranch)
            if (then == null || otherwise == null) null else then + otherwise
        }
        is JSLiteralExpression -> if (valueOf(expression) != null) listOf(expression) else null
        else -> null
    }

    /** A quoted string, or a template writing no `${…}`. */
    private fun valueOf(literal: JSLiteralExpression): String? = when {
        literal is JSStringTemplateExpression ->
            literal.text.takeIf { literal.arguments.isEmpty() && it.length >= 2 }?.removeSurrounding("`")
        literal.isQuotedLiteral -> literal.stringValue
        else -> null
    }

    /** What [literal] initializes, and whether it is one branch of a conditional. */
    private data class Declared(val owner: PsiElement, val branch: Boolean)

    /**
     * The constant [literal] is the value of — read from the PSI alone, so it costs no search: the
     * annotator asks it of every literal. Whether the constant is ever reassigned is [constant]'s.
     */
    private fun declaredBy(literal: PsiElement): Declared? {
        if (literal !is JSLiteralExpression || valueOf(literal) == null) return null
        var expression: PsiElement = literal
        var branch = false
        while (true) {
            val parent = expression.parent
            expression = when {
                parent is JSParenthesizedExpression || parent is TypeScriptAsExpression -> parent
                parent is JSConditionalExpression && (parent.thenBranch === expression || parent.elseBranch === expression) -> {
                    branch = true
                    parent
                }
                else -> break
            }
        }
        val owner = when (val parent = expression.parent) {
            is TypeScriptEnumField -> parent.takeIf { !branch && it.initializer === expression }
            // A parameter's default value is no constant: the caller passes another one.
            is JSParameter -> null
            is JSVariable -> parent.takeIf { it.initializer === expression }
            is JSProperty -> parent.takeIf { !branch && it.value === expression && inConstantObject(it) }
            else -> null
        } ?: return null
        return Declared(owner, branch)
    }

    /** True when [property] belongs to an object literal — nested ones included — initializing a `const`. */
    private fun inConstantObject(property: JSProperty): Boolean {
        var current: PsiElement? = property.parent as? JSObjectLiteralExpression ?: return false
        while (current != null) {
            val parent = current.parent
            when (parent) {
                is JSParenthesizedExpression, is TypeScriptAsExpression -> current = parent
                is JSProperty -> current = parent.parent as? JSObjectLiteralExpression
                is JSVariable -> return parent.isConst && parent.initializer === current
                else -> return false
            }
        }
        return false
    }

    /**
     * True when [owner] keeps its initial value: an `enum` member, a property, a `const`, or a `let`
     * no code assigns — searched in its own scope only, so an exported `let` never qualifies.
     */
    private fun constant(owner: PsiElement): Boolean {
        if (owner !is JSVariable || owner is TypeScriptEnumField || owner.isConst) return true
        return CachedValuesManager.getCachedValue(owner) {
            CachedValueProvider.Result.create(neverAssigned(owner), PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    /** Searched by name in code, as [searchUsingArgument] is, and for the same reason. */
    private fun neverAssigned(variable: JSVariable): Boolean {
        val scope = variable.useScope as? LocalSearchScope ?: return false
        val name = variable.name ?: return false
        val detector = ReadWriteAccessDetector.findDetector(variable) ?: return false
        return namedIn(scope, name, variable).none { detector.getExpressionAccess(it) != ReadWriteAccessDetector.Access.Read }
    }

    /**
     * The references named [name] in [scope]'s code that resolve to [owner], an import followed;
     * only those passing [candidate] are resolved, and the search ends at the first one matching [stop].
     *
     * [candidate] is a PSI-only test run before `resolve()`: in TypeScript, resolving asks the
     * language service, and every word spelling the name (`var` in an Angular type-check block,
     * a property of the same name) cost a request — some of which the service fails on (TS 7).
     *
     * One file at a time, on the calling thread: the search runs inside the reference provider and
     * the annotators, themselves run in parallel by the daemon. A search over a global scope would
     * spread its files over that same pool, and the exception stopping its tasks — at the first
     * match, or when the daemon cancels — escaped the nested search (`ComputationAbortedException`).
     * A search over a single element's local scope runs on the caller's thread.
     */
    private fun namedIn(
        scope: SearchScope,
        name: String,
        owner: PsiElement,
        candidate: (JSReferenceExpression) -> Boolean = { true },
        stop: (JSReferenceExpression) -> Boolean = { false },
    ): List<JSReferenceExpression> {
        val helper = PsiSearchHelper.getInstance(owner.project)
        val found = mutableListOf<JSReferenceExpression>()
        val searchIn = { root: PsiElement ->
            helper.processElementsWithWord(
                { element, _ ->
                    if (element is JSReferenceExpression && element.referenceName == name && candidate(element) &&
                        element.resolve()?.let(::declarationOf) == owner
                    ) {
                        found += element
                        !stop(element)
                    } else {
                        true
                    }
                },
                LocalSearchScope(root),
                name,
                UsageSearchContext.IN_CODE,
                true,
            )
        }
        val roots: Sequence<PsiElement> = when (scope) {
            is LocalSearchScope -> scope.scope.asSequence()
            is GlobalSearchScope -> candidateFiles(helper, scope, name).asSequence().mapNotNull { PsiManager.getInstance(owner.project).findFile(it) }
            else -> emptySequence()
        }
        for (root in roots) {
            if (!searchIn(root)) break
        }
        return found
    }

    /** The files of [scope] whose code holds the word [name], collected before any of them is read. */
    private fun candidateFiles(helper: PsiSearchHelper, scope: GlobalSearchScope, name: String): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        helper.processCandidateFilesForText(scope, UsageSearchContext.IN_CODE, true, name) { files += it; true }
        return files
    }

    /**
     * The first argument naming [owner] in a translation call, or null — cached until the PSI
     * changes, as a search over the constant's use scope: the reference provider and the annotator
     * both ask it.
     */
    private fun usingArgument(owner: PsiElement): PsiElement? =
        CachedValuesManager.getCachedValue(owner) {
            CachedValueProvider.Result.create(searchUsingArgument(owner), PsiModificationTracker.MODIFICATION_COUNT)
        }

    /**
     * The calls are found by the constant's name, in code only: a reference search would also read
     * the references of every literal spelling the name (`obj['save']`), the constant's own literal
     * among them, whose provider asks this again — endlessly. A name counts when it resolves to [owner].
     */
    private fun searchUsingArgument(owner: PsiElement): PsiElement? {
        if (!constant(owner)) return null
        val name = (owner as? PsiNamedElement)?.name ?: return null
        val names = translationFunctionNames()
        val isKey = { reference: JSReferenceExpression -> isKeyArgument(reference, names) }
        return namedIn(owner.useScope, name, owner, candidate = isKey, stop = isKey).firstOrNull()
    }
}

/**
 * Ctrl+click on the constant of `t(KEYS.save)` opens the key's translation in the preview locale,
 * as on a literal key ([I18nGotoDeclarationHandler.previewTarget]).
 *
 * The constant already carries the IDE's own JavaScript reference, to its declaration, and an
 * `I18nReference` there would make Shift+F6 rewrite the call itself: this handler resolves the key
 * instead, and runs first. Only on the constant's name — its qualifier (`KEYS`) keeps the JavaScript
 * navigation — and only when the key resolves somewhere.
 */
class ConstantKeyGotoDeclarationHandler : GotoDeclarationHandler, CompositeKeyResolver<PsiElement> {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val argument = sourceElement?.parent as? JSReferenceExpression ?: return null
        if (argument.referenceNameElement !== sourceElement) return null
        val rawKey = ConstantKeyResolver.callKey(argument) ?: return null
        val project = argument.project
        val fullKey = RawKeyParser(project).parse(rawKey, argument) ?: return null
        val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
        val descriptors = project.service<LocalizationSourceService>().findReadSources(fullKey, argument)
            .flatMap { source -> resolve(fullKey, source, pluralSeparator).map { ReferenceDescriptor(it, source.host) } }
            .filter { it.reference.path.isNotEmpty() }
        if (descriptors.isEmpty()) return null
        val reference = I18nReference(argument, TextRange(0, argument.textLength), descriptors)
        return I18nGotoDeclarationHandler.previewTarget(reference)
            ?: reference.multiResolve(false).mapNotNull { it.element }.toTypedArray().takeIf { it.isNotEmpty() }
    }
}
