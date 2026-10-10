package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.lang.js.extractors.MessageDescriptors
import com.ibrahimdans.i18n.extensions.lang.js.extractors.SvelteI18nExtractor
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.extensions.lang.js.extractors.XmlAttributeKeyExtractor
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
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSSpreadExpression
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

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
 *  - The argument of an ICU block — `count` in `{count, plural, one {# item} other {# items}}`,
 *    likewise `select` and `selectordinal` — is, where single braces are: it picks the branch, and
 *    forgetting it is the commonest mistake. Read here because the dialog's rule cannot match a
 *    `{…}` holding braces; only a block at the top level, not one nested in another's branch.
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
 *  - svelte-i18n passes its variables under `values`: `$_('key', { values: { name } })`;
 *  - react-intl writes the key as the `id` of a descriptor and passes the values next to it:
 *    `intl.formatMessage({ id: 'key' }, { name })` — the descriptor's id is the key literal
 *    [com.ibrahimdans.i18n.extensions.lang.js.extractors.ReactIntlExtractor] reads, so the key resolves as the annotator resolves it;
 *  - vue-i18n's `$tc('key', choice, { name })` — a locale may stand before the values — always
 *    passes `count` and `n` as well: vue-i18n fills both from the choice by itself;
 *  - the components pass the values as an attribute: react-intl's
 *    `<FormattedMessage id="key" values={{ name }} />` and react-i18next's
 *    `<Trans i18nKey="key" values={{ name }} />`. The key is the attribute value the key extraction
 *    reads; `values` must be an object literal written inline. A `<Trans>` holding children takes
 *    its message from them — `<Trans>Hello {{name}}</Trans>` — and is left alone. Without a module
 *    preset, `FormattedMessage` is react-intl's (single braces) and `Trans` i18next's.
 *
 * All forms of a plural are read: a variable used only in `item_other` is still needed.
 */
class InterpolationArgumentsInspection : LocalInspectionTool(), CompositeKeyResolver<PsiElement> {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nInterpolationArguments"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        // A plain visitor: JS calls and JSX tags both fall back to visitElement.
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JSCallExpression -> checkCall(element, holder)
                    is XmlTag -> checkComponent(element, holder)
                }
            }
        }
    }

    /** `<FormattedMessage id="key" values={{ name }} />`, `<Trans i18nKey="key" values={{ name }} />`. */
    private fun checkComponent(tag: XmlTag, holder: ProblemsHolder) {
        val keyAttribute = COMPONENT_KEYS[tag.name] ?: return
        if (tag.name == TRANS && hasChildren(tag)) return
        val key = tag.getAttribute(keyAttribute)?.valueElement ?: return
        if (key.children.any { it is JSEmbeddedContent }) return
        val supplied = componentValues(tag) ?: return
        val frameworks = ModulePresets.presetOf(key)?.let(::setOf) ?: setOf(COMPONENT_FRAMEWORKS.getValue(tag.name))
        val singleBraces = frameworks.all { it in SINGLE_BRACE_FRAMEWORKS }
        // `i18nKey` is read by the key extraction only around a call: the attribute is read here.
        val rawKey = if (tag.name == TRANS) XmlAttributeKeyExtractor().extract(key) else null
        val expected = expectedVariables(key, singleBraces, rawKey) ?: return
        report(key, expected - supplied, holder)
    }

    /** The names of the inline object literal of [tag]'s `values`, none without it, or null when unknown. */
    private fun componentValues(tag: XmlTag): Set<String>? {
        val values = tag.getAttribute(VALUES)?.valueElement ?: return emptySet()
        val embedded = values.children.firstOrNull { it is JSEmbeddedContent } ?: return null
        val literal = PsiTreeUtil.getChildOfType(embedded, JSExpression::class.java) as? JSObjectLiteralExpression ?: return null
        return propertyNames(literal, nested = null)
    }

    /** True when [tag] holds a tag or a text: `<Trans>` then takes its message from them. */
    private fun hasChildren(tag: XmlTag): Boolean =
        tag.subTags.isNotEmpty() || tag.value.children.any { it !is PsiWhiteSpace && it.text.isNotBlank() }

    private fun checkCall(call: JSCallExpression, holder: ProblemsHolder) {
        val (keyLiteral, supplied) = readCall(call) ?: return
        val expected = expectedVariables(keyLiteral, interpolatesSingleBraces(call, keyLiteral)) ?: return
        report(keyLiteral, expected - supplied, holder)
    }

    private fun report(key: PsiElement, missing: Set<String>, holder: ProblemsHolder) {
        if (missing.isEmpty()) return
        holder.registerProblem(
            key,
            PluginBundle.message("inspection.interpolation.arguments.message", missing.sorted().joinToString(", "))
        )
    }

    /**
     * The literal [call] writes its key in, and the variables it passes, or null when either is
     * unknown statically. See the class documentation for the shapes understood.
     */
    private fun readCall(call: JSCallExpression): Pair<JSLiteralExpression, Set<String>>? {
        val first = call.arguments.firstOrNull() ?: return null
        if (first is JSObjectLiteralExpression) return readFormatMessage(call, first)
        val keyLiteral = first as? JSLiteralExpression ?: return null
        if (!keyLiteral.isQuotedLiteral) return null
        val supplied = (if (isTc(call)) tcVariables(call) else suppliedVariables(call, keyLiteral)) ?: return null
        return keyLiteral to supplied
    }

    /** `formatMessage({ id: 'key' }, values)`: the descriptor's id, and the values' names. */
    private fun readFormatMessage(call: JSCallExpression, descriptor: JSObjectLiteralExpression): Pair<JSLiteralExpression, Set<String>>? {
        val id = descriptor.findProperty("id")?.value as? JSLiteralExpression ?: return null
        if (!id.isQuotedLiteral || !MessageDescriptors.isFormatMessageDescriptor(id)) return null
        val values = call.arguments.getOrNull(1) ?: return id to emptySet()
        val supplied = (values as? JSObjectLiteralExpression)?.let { propertyNames(it, nested = null) } ?: return null
        return id to supplied
    }

    /** True for vue-i18n's `$tc`, called bare or as `this.$tc`. */
    private fun isTc(call: JSCallExpression): Boolean =
        (call.methodExpression as? JSReferenceExpression)?.referenceName == TC

    /**
     * What `$tc(key, choice, [locale], [values])` passes: `count` and `n`, which vue-i18n fills
     * from the choice, plus the values' names.
     */
    private fun tcVariables(call: JSCallExpression): Set<String>? {
        val arguments = call.arguments
        val values: JSExpression = when {
            arguments.size < 3 -> return TC_IMPLICIT
            isStringLiteral(arguments[2]) -> arguments.getOrNull(3) ?: return TC_IMPLICIT
            else -> arguments[2]
        }
        val literal = values as? JSObjectLiteralExpression ?: return null
        return TC_IMPLICIT + (propertyNames(literal, nested = null) ?: return null)
    }

    /**
     * The variables the value of the key written in [keyLiteral] — a JS literal or a JSX attribute
     * value — uses in the reference locale, or null when that value cannot be read with certainty.
     */
    private fun expectedVariables(keyLiteral: PsiElement, singleBraces: Boolean, knownKey: RawKey? = null): Set<String>? {
        val project = keyLiteral.project
        val rawKey = knownKey ?: run {
            val translationFunctionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }
            val lang = Extensions.LANG.extensionList.firstOrNull { it.canExtractKey(keyLiteral, translationFunctionNames) }
                ?: return null
            lang.extractRawKey(keyLiteral)
        } ?: return null
        val fullKey = RawKeyParser(project).parse(rawKey, keyLiteral) ?: return null
        if (fullKey.isDynamic) return null

        val sources = project.service<LocalizationSourceService>().findReadSources(fullKey, keyLiteral)
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

        /** vue-i18n's plural function, and the variables it always passes. */
        private const val TC = "\$tc"
        private val TC_IMPLICIT = setOf("count", "n")

        private const val TRANS = "Trans"
        private const val VALUES = "values"

        /** The attribute each translation component writes its key in. */
        private val COMPONENT_KEYS = mapOf("FormattedMessage" to "id", TRANS to "i18nKey")

        /** The technology of a component when no module preset says otherwise. */
        private val COMPONENT_FRAMEWORKS = mapOf("FormattedMessage" to "react-intl", TRANS to "i18next")

        /** The technologies for which `{name}` is a variable rather than text. */
        internal val SINGLE_BRACE_FRAMEWORKS = setOf("vue-i18n", "lingui", "react-intl", "next-intl", SVELTE_I18N)

        /**
         * The names of the variables [text] fills from the call's options, in the order they
         * appear. Positional (`%s`) and ICU-branch (`{…}` nested in braces) matches are left out,
         * and so is anything that is not a JavaScript identifier once its decorations are removed.
         * Single-brace `{name}` counts only when [singleBraces] says the technology interpolates it.
         */
        fun namedVariables(text: String, singleBraces: Boolean): Set<String> {
            val variables = DialogViewModel.variableRanges(text)
                .filter { range -> braceDepthBefore(text, range.first) == 0 }
                .mapNotNull { range -> variableName(text.substring(range), singleBraces) }
                .toSet()
            return if (singleBraces) variables + icuArguments(text) else variables
        }

        /** `{count, plural, one {# item} other {# items}}` → `count`, for blocks at the top level of [text]. */
        private fun icuArguments(text: String): List<String> =
            ICU_ARGUMENT.findAll(text)
                .filter { braceDepthBefore(text, it.range.first) == 0 }
                .map { it.groupValues[1] }
                .toList()

        private val ICU_ARGUMENT = Regex("""\{\s*([A-Za-z_$][\w$]*)\s*,\s*(?:plural|select|selectordinal)\s*,""")

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
