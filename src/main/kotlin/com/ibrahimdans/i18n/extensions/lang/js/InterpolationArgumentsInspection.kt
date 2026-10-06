package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.SvelteI18nExtractor
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSElementVisitor
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a translation call that does not pass a variable its message needs:
 * `greeting = "Hello {{name}}"` called as `t('greeting')` renders `Hello {{name}}` in production.
 * [com.ibrahimdans.i18n.plugin.ide.inspection.PlaceholderConsistencyInspection] compares the
 * locales with each other; nothing compared the call with the value until now.
 *
 * The message is the key's value in the module's reference locale (`en` when none is declared),
 * read through the pipeline the annotator and the inlay hints use — [com.ibrahimdans.i18n.Lang],
 * [RawKeyParser], [LocalizationSourceService], [CompositeKeyResolver] — so a key is found here
 * exactly when it is found there. Its variables come from [DialogViewModel.variableRanges], the
 * rule the translation dialog already applies, rather than from a regex of its own.
 *
 * Which variables are *named options* of the call:
 *  - `{{name}}` (i18next, ngx-translate) always is: it is filled from the object passed to the
 *    call. So is `%{name}` (i18n-js): no other technology writes it, so it needs no technology
 *    check — which matters, since `t` is published by i18next as well as by i18n-js. `{{- name}}` and `{{value, number}}` name `name` and `value`; `{{user.name}}` is filled
 *    from `user`, so `user` is what the object must hold.
 *  - `{name}` is only where the call's technology interpolates single braces — vue-i18n, lingui,
 *    react-intl / ICU MessageFormat, svelte-i18n ([SINGLE_BRACE_FRAMEWORKS]). For i18next it is
 *    literal text: asking `t('k')` for a `name` there would be a false positive.
 *    The technology is the one the plugin already knows, never a new detection: the preset of the
 *    module holding the file ([ModulePresets.presetOf]) when one is set, otherwise the technologies
 *    publishing the called function's name ([com.ibrahimdans.i18n.Technology.translationFunctionNames]
 *    — `$t` is vue-i18n's, `i18n._` lingui's, `t` i18next's and i18n-js'), svelte-i18n's `$_`
 *    being recognised by [SvelteI18nExtractor] as the key extraction does. Every candidate must
 *    interpolate single braces; when none is known (a name a key assistance rule includes, say),
 *    `{name}` is not counted — silence rather than a guess. A vue-i18n project calling `t` from
 *    `useI18n()` without a module preset therefore falls under i18next's rule.
 *  - `%s` / `%1$s` (sprintf) are not: they are positional, filled from an array, never by name.
 *  - A `{…}` inside another pair of braces is not either: it is an ICU branch (`one {# item}`,
 *    `male {He}`), text rather than a variable.
 *
 * Silence wins over a guess, since a false warning on every call teaches people to disable the
 * inspection. Nothing is reported when:
 *  - the options are not an object literal (a variable, a call, a spread inside the object, a
 *    computed property name): what they hold is only known at runtime;
 *  - the key does not resolve in the reference locale (the annotator and *Translation key missing
 *    from a locale* say so already), is dynamic, or resolves onto an object;
 *  - the key is not a plain string literal.
 *
 * Argument shapes understood:
 *  - `t('key')` and `t('key', 'Default value')` pass no variable at all;
 *  - `t('key', { name })`, and `t('key', 'Default value', { name })` (i18next's default value,
 *    vue-i18n's `$t(key, locale, values)`) pass the object's properties;
 *  - i18next's `replace: { name }` counts as well, and `count` is an ordinary property: a plural
 *    whose forms use `{{count}}` needs it like any other variable;
 *  - svelte-i18n passes its variables under `values`: `$_('key', { values: { name } })`.
 *
 * All forms of a plural are read: a variable used only in `item_other` is still needed.
 */
class InterpolationArgumentsInspection : LocalInspectionTool(), CompositeKeyResolver<PsiElement> {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nInterpolationArguments"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        return object : JSElementVisitor() {
            override fun visitJSCallExpression(node: JSCallExpression) {
                checkCall(node, holder)
            }
        }
    }

    private fun checkCall(call: JSCallExpression, holder: ProblemsHolder) {
        val keyLiteral = call.arguments.firstOrNull() as? JSLiteralExpression ?: return
        if (!keyLiteral.isQuotedLiteral) return
        val supplied = suppliedVariables(call, keyLiteral) ?: return
        val expected = expectedVariables(keyLiteral, interpolatesSingleBraces(call, keyLiteral)) ?: return
        val missing = expected - supplied
        if (missing.isEmpty()) return
        holder.registerProblem(
            keyLiteral,
            PluginBundle.message("inspection.interpolation.arguments.message", missing.sorted().joinToString(", "))
        )
    }

    /**
     * The variables the value of the key written in [keyLiteral] uses in the reference locale, or
     * null when that value cannot be read with certainty.
     */
    private fun expectedVariables(keyLiteral: JSLiteralExpression, singleBraces: Boolean): Set<String>? {
        val project = keyLiteral.project
        val translationFunctionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
        val lang = Extensions.LANG.extensionList.firstOrNull { it.canExtractKey(keyLiteral, translationFunctionNames) }
            ?: return null
        val rawKey = lang.extractRawKey(keyLiteral) ?: return null
        val fullKey = RawKeyParser(project).parse(rawKey, keyLiteral) ?: return null
        if (fullKey.isDynamic) return null

        val sources = project.service<LocalizationSourceService>().findSources(fullKey.allNamespaces(), keyLiteral)
        if (sources.isEmpty()) return null
        val referenceLocale = TranslationFileScope.referenceLocaleFor(keyLiteral.containingFile, sources.first())
        val locale = LocaleMatching.pick(referenceLocale, sources.map { it.localeLabel() }) ?: return null

        val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
        val resolved = sources
            .filter { it.localeLabel() == locale }
            .flatMap { resolve(fullKey.compositeKey, it, pluralSeparator) }
            .filter { it.unresolved.isEmpty() }
        if (resolved.isEmpty()) return null

        val variables = mutableSetOf<String>()
        for (reference in resolved) {
            val values = valuesOf(reference.element) ?: return null
            values.forEach { variables += namedVariables(it, singleBraces) }
        }
        return variables
    }

    /**
     * True when every technology [call] may belong to reads `{name}` as a variable, false when one
     * does not or none is known. See the class documentation.
     */
    private fun interpolatesSingleBraces(call: JSCallExpression, keyLiteral: JSLiteralExpression): Boolean {
        val frameworks = ModulePresets.presetOf(keyLiteral)?.let(::setOf) ?: frameworksPublishing(call, keyLiteral)
        return frameworks.isNotEmpty() && frameworks.all { it in SINGLE_BRACE_FRAMEWORKS }
    }

    /** The ids of the technologies that publish the function [call] invokes. */
    private fun frameworksPublishing(call: JSCallExpression, keyLiteral: JSLiteralExpression): Set<String> {
        if (SvelteI18nExtractor().canExtract(keyLiteral)) return setOf(SVELTE_I18N)
        val callee = (call.methodExpression as? JSReferenceExpression)?.text ?: return emptySet()
        // Published either qualified (`i18n._`) or by method name (`$t`, matched on `this.$t`).
        val names = setOf(callee, callee.substringAfterLast('.'))
        return Extensions.TECHNOLOGY.extensionList
            .filter { technology -> technology.translationFunctionNames().any { it in names } }
            .mapNotNullTo(mutableSetOf()) { it.frameworkId() }
    }

    /** The texts of a leaf, or of every form of a nested plural group; null for any other object. */
    private fun valuesOf(node: Tree<PsiElement>?): List<String>? {
        if (node == null) return null
        if (node.isLeaf()) return listOfNotNull(textOf(node))
        if (!PluralGroup.isPluralGroup(node)) return null
        val forms = node.findChildren("")
        if (forms.any { !it.isLeaf() }) return null
        return forms.mapNotNull(::textOf)
    }

    private fun textOf(node: Tree<PsiElement>): String? = node.value().text?.unQuote()

    /**
     * The variables of [call]'s options, or null when they cannot be known statically.
     * See the class documentation for the argument shapes understood.
     */
    private fun suppliedVariables(call: JSCallExpression, keyLiteral: JSLiteralExpression): Set<String>? {
        val arguments = call.arguments
        val options: JSExpression = when {
            arguments.size < 2 -> return emptySet()
            arguments[1] is JSObjectLiteralExpression -> arguments[1]
            isStringLiteral(arguments[1]) -> arguments.getOrNull(2) ?: return emptySet()
            else -> return null
        }
        val literal = options as? JSObjectLiteralExpression ?: return null
        if (!SvelteI18nExtractor().canExtract(keyLiteral)) return propertyNames(literal, nested = "replace")
        // svelte-i18n: `{ values: { … }, default: '…' }`, only `values` interpolates.
        if (hasUnknownShape(literal)) return null
        val values = literal.findProperty("values") ?: return emptySet()
        return (values.value as? JSObjectLiteralExpression)?.let { propertyNames(it, nested = null) }
    }

    /**
     * The property names of [literal], plus those of its [nested] object literal property, or null
     * when either holds something whose names are only known at runtime.
     */
    private fun propertyNames(literal: JSObjectLiteralExpression, nested: String?): Set<String>? {
        if (hasUnknownShape(literal)) return null
        val names = mutableSetOf<String>()
        for (property in literal.properties) {
            val name = property.name ?: return null
            names += name
            if (name == nested) {
                val inner = property.value as? JSObjectLiteralExpression ?: return null
                names += propertyNames(inner, nested = null) ?: return null
            }
        }
        return names
    }

    /** True when [literal] spreads another object or computes a property name. */
    private fun hasUnknownShape(literal: JSObjectLiteralExpression): Boolean =
        literal.children.any { it is JSSpreadExpression } ||
            literal.properties.any { it.name == null || it.computedPropertyName != null }

    private fun isStringLiteral(expression: JSExpression): Boolean =
        expression is JSLiteralExpression && expression.isQuotedLiteral

    internal companion object {

        private const val SVELTE_I18N = "svelte-i18n"

        /** The technologies for which `{name}` is a variable rather than text. */
        private val SINGLE_BRACE_FRAMEWORKS = setOf("vue-i18n", "lingui", "react-intl", SVELTE_I18N)

        /**
         * The names of the variables [text] fills from the call's options, in the order they
         * appear. Positional (`%s`) and ICU-branch (`{…}` nested in braces) matches are left out,
         * and so is anything that is not a JavaScript identifier once its decorations are removed.
         * Single-brace `{name}` counts only when [singleBraces] says the technology interpolates it.
         */
        fun namedVariables(text: String, singleBraces: Boolean): Set<String> =
            DialogViewModel.variableRanges(text)
                .filter { range -> braceDepthBefore(text, range.first) == 0 }
                .mapNotNull { range -> variableName(text.substring(range), singleBraces) }
                .toSet()

        /** `{{- user.name, uppercase}}` → `user`; `%{count}` → `count`; `{amount, number}` → `amount`; `%s` → null. */
        private fun variableName(token: String, singleBraces: Boolean): String? {
            val compact = token.filterNot { it.isWhitespace() }
            val inner = when {
                compact.startsWith("{{") -> compact.removePrefix("{{").removeSuffix("}}").removePrefix("-")
                compact.startsWith("%{") -> compact.removePrefix("%{").removeSuffix("}")
                compact.startsWith("{") && singleBraces -> compact.removePrefix("{").removeSuffix("}")
                else -> return null
            }
            val name = inner.substringBefore(',').substringBefore('.')
            return name.takeIf(::isIdentifier)
        }

        private fun isIdentifier(name: String): Boolean =
            name.isNotEmpty() &&
                (name[0].isLetter() || name[0] == '_' || name[0] == '$') &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '$' }

        /** How many braces are open at [offset] of [text]: above zero, a match is an ICU branch. */
        private fun braceDepthBefore(text: String, offset: Int): Int {
            var depth = 0
            for (index in 0 until offset) {
                when (text[index]) {
                    '{' -> depth++
                    '}' -> if (depth > 0) depth--
                }
            }
            return depth
        }
    }
}
