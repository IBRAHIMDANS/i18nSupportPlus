package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.extensions.lang.js.HardcodedTextRules
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * Which hard-coded strings *Internationalize…* offers, and which it offers unchecked.
 *
 * Two levels, because a wrong extraction does not cost the same everywhere:
 *  - [isNeverProposed]: the string is code by its place — a hook's namespace, an import, a
 *    `className`. Extracting it breaks the code (`useTranslation(t('account'))`), so it is not
 *    offered at all.
 *  - [uncheckedReason]: the string looks like data rather than a message — a URL, an identifier,
 *    a colour. It may still be shown to a user (a link's text can be its URL), so it is offered,
 *    unchecked, with the reason.
 */
internal object TranslatableText {

    /** Why a text is offered unchecked; the preview shows it next to the text. */
    enum class Reason {
        URL, PATH, IDENTIFIER, CSS_CLASS, COLOR, DATE, NUMBER, SYMBOLS;

        /** One literal key per value, as `PluginBundleTest` requires. */
        fun message(): String = when (this) {
            URL -> PluginBundle.message("action.i18nize.reason.url")
            PATH -> PluginBundle.message("action.i18nize.reason.path")
            IDENTIFIER -> PluginBundle.message("action.i18nize.reason.identifier")
            CSS_CLASS -> PluginBundle.message("action.i18nize.reason.css_class")
            COLOR -> PluginBundle.message("action.i18nize.reason.color")
            DATE -> PluginBundle.message("action.i18nize.reason.date")
            NUMBER -> PluginBundle.message("action.i18nize.reason.number")
            SYMBOLS -> PluginBundle.message("action.i18nize.reason.symbols")
        }
    }

    /**
     * Calls whose arguments are never messages: translation hooks, whose strings are namespaces
     * (`useTranslation('ns')`, `useTranslations('Home')`, `getTranslations('Home')`), translation
     * functions, whose strings are keys or options (`t('key', { ns: 'x' })`), and module loaders.
     * Matched on the callee's last name, so `i18n.getFixedT` and `this.$t` count.
     */
    private val CODE_CALLS = setOf(
        "useTranslation", "useTranslations", "getTranslations", "useI18n", "withTranslation",
        "getFixedT", "loadNamespaces", "changeLanguage",
        "t", "\$t", "\$tc", "\$te", "_", "\$_", "formatMessage",
        "require", "import",
    )

    /** Translation options whose value is a namespace or a key prefix, wherever they are written. */
    private val NAMESPACE_OPTIONS = setOf("ns", "keyPrefix", "namespace")

    /**
     * Attributes whose value is code. The ARIA attributes are, except those a screen reader reads
     * out ([HardcodedTextRules.VISIBLE_ATTRIBUTES]: `aria-label`, `aria-description`…) — the others
     * name an element by id (`aria-labelledby`, `aria-controls`) or hold a state (`aria-hidden`).
     */
    private val TECHNICAL_ATTRIBUTES = setOf(
        "className", "class", "id", "key", "name", "type", "role", "rel", "target", "href", "src", "srcSet",
        "htmlFor", "for", "style", "ref", "lang", "dir", "method", "action", "autoComplete", "inputMode",
        "viewBox", "xmlns",
    )

    /**
     * True when [literal] — a candidate's string token, JS or JSX — is code by its place, whatever
     * it holds: an argument of a [CODE_CALLS] call, a [NAMESPACE_OPTIONS] value, an import or a
     * directive (`'use client'`), or the value of a technical attribute ([isTechnicalAttribute]).
     */
    fun isNeverProposed(literal: PsiElement): Boolean =
        isModuleOrDirective(literal) || isInCodeCall(literal) || isNamespaceOption(literal) ||
            attributeOf(literal)?.let { isTechnicalAttribute(it.name) } == true

    fun isTechnicalAttribute(name: String): Boolean =
        name in TECHNICAL_ATTRIBUTES || name.startsWith("data-") ||
            (name.startsWith("aria-") && name !in HardcodedTextRules.VISIBLE_ATTRIBUTES)

    /**
     * Why [text] is offered unchecked, or null when it reads as a message — `OK`, `Save`,
     * `Hello {{name}}` stay checked. Each rule requires the whole text to match, and the
     * identifier rules a text without space: a sentence is never taken for data.
     */
    fun uncheckedReason(text: String): Reason? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> null
            ISO_DATE.matches(trimmed) || isDateFormat(trimmed) -> Reason.DATE
            COLORS.any { it.matches(trimmed) } -> Reason.COLOR
            trimmed.split(WHITESPACE).all { NUMBER.matches(it) } -> Reason.NUMBER
            URLS.any { it.matches(trimmed) } -> Reason.URL
            isPath(trimmed) -> Reason.PATH
            trimmed.none { it.isLetter() } -> Reason.SYMBOLS
            !trimmed.contains(WHITESPACE) && IDENTIFIERS.any { it.matches(trimmed) } -> Reason.IDENTIFIER
            isCssClasses(trimmed) -> Reason.CSS_CLASS
            else -> null
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private val ISO_DATE = Regex(
        "\\d{4}-\\d{2}(-\\d{2})?([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?|\\d{2}:\\d{2}(:\\d{2})?"
    )

    /** A date pattern of moment / date-fns / dayjs: `YYYY-MM-DD`, `dd/MM/yyyy`, `HH:mm`. */
    private val DATE_TOKEN = "(?:YYYY|YY|yyyy|yy|MMMM|MMM|MM|M|DD|Do|D|dddd|ddd|dd|d|EEEE|EEE|HH|H|hh|h|mm|m|ss|s|SSS|A|a|ZZ|Z)"
    private val DATE_FORMAT = Regex("$DATE_TOKEN(?:[-/.:, T]+$DATE_TOKEN)+")
    private val DATE_FIELD = Regex("YY|yy|MM|DD|dd|HH|hh|mm|ss")

    private fun isDateFormat(text: String): Boolean = DATE_FORMAT.matches(text) && DATE_FIELD.containsMatchIn(text)

    private val COLORS = listOf(
        Regex("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"),
        Regex("(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\\([^)]*\\)", RegexOption.IGNORE_CASE),
        Regex("transparent|currentColor"),
    )

    /** A number, with a CSS unit if any: `42`, `-1.5`, `1,000`, `16px`, `50%`, `0x1F`. */
    private val NUMBER = Regex(
        "[-+]?(?:\\d[\\d_,]*)?\\.?\\d+(?:e[-+]?\\d+)?(?:px|rem|em|vh|vw|vmin|vmax|%|ms|s|pt|deg|fr|ch|ex|x)?|0x[0-9a-f]+",
        RegexOption.IGNORE_CASE
    )

    private val URLS = listOf(
        Regex("(?:[a-z][a-z0-9+.-]*:)?//\\S+", RegexOption.IGNORE_CASE),
        Regex("(?:mailto|tel|data|javascript):\\S+", RegexOption.IGNORE_CASE),
        Regex("www\\.\\S+", RegexOption.IGNORE_CASE),
    )

    private val PATH_START = Regex("(?:/|\\./|\\.\\./|~/|@/|@[\\w-]+/).*")
    private val FILE_NAME = Regex(
        "[\\w@.-]*[\\w-]\\.(?:png|jpe?g|gif|svg|webp|avif|ico|json|ya?ml|js|mjs|cjs|ts|tsx|jsx|vue|css|scss|less|html?|pdf|txt|md|csv|xml|woff2?|ttf|mp4|mp3)",
        RegexOption.IGNORE_CASE
    )

    /**
     * `/home`, `./logo.png`, `assets/img/logo.png`, `logo.png`. A single slash between two words
     * is not enough: `and/or`, `km/h` are words.
     */
    private fun isPath(text: String): Boolean {
        if (text.contains(WHITESPACE)) return false
        if (PATH_START.matches(text) || text.contains('\\')) return true
        val last = text.substringAfterLast('/')
        return FILE_NAME.matches(last) || text.count { it == '/' } >= 2
    }

    private val IDENTIFIERS = listOf(
        // camelCase: one hump at least — `Save` or `OK` has none.
        Regex("[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+"),
        // snake_case, SCREAMING_SNAKE_CASE, _private
        Regex("_*[A-Za-z][A-Za-z0-9]*(?:_+[A-Za-z0-9]+)+|_+[A-Za-z0-9]+"),
        // kebab-case, lower case only: `Wi-Fi` is a word.
        Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)+"),
        // dotted path: `user.name`, `config.api.url`
        Regex("[a-z_$][\\w$]*(?:\\.[\\w$]+)+"),
    )

    private val CSS_TOKEN = Regex("[!-]?[a-z0-9][a-z0-9!\\-:/_.\\[\\]%#()]*")
    private val CSS_MARK = Regex("[a-z].*[-:_\\[/]|[-:_\\[/].*[a-z]")

    /**
     * `flex items-center gap-2`, `btn btn-primary`, `md:flex`: lower-case class names, half of them
     * at least written with a `-`, `:`, `_`, `[` or `/`. `save changes` has none; a lone word only
     * counts with a variant (`md:flex`) or an arbitrary value (`w-[200px]`), the plain `text-sm`
     * being an identifier already.
     */
    private fun isCssClasses(text: String): Boolean {
        val tokens = text.split(WHITESPACE)
        if (!tokens.all { CSS_TOKEN.matches(it) }) return false
        val marked = tokens.count { CSS_MARK.containsMatchIn(it) }
        if (tokens.size == 1) return tokens[0].contains(':') || tokens[0].contains('[')
        return marked * 2 >= tokens.size
    }

    private fun isModuleOrDirective(literal: PsiElement): Boolean {
        if (PsiTreeUtil.getParentOfType(literal, ES6ImportExportDeclaration::class.java) != null) return true
        val expression = literal.parent as? JSLiteralExpression ?: return false
        return (expression.parent as? JSExpressionStatement)?.expression == expression
    }

    private fun isInCodeCall(literal: PsiElement): Boolean {
        for (element in ancestors(literal)) {
            val call = (element as? JSArgumentList)?.parent as? JSCallExpression ?: continue
            if (calleeName(call) in CODE_CALLS) return true
        }
        return false
    }

    private fun calleeName(call: JSCallExpression): String? =
        (call.methodExpression as? JSReferenceExpression)?.referenceName ?: call.firstChild?.text

    /** `{ ns: 'account' }`, `{ ns: ['a', 'b'] }`, `{ keyPrefix: 'form' }`. */
    private fun isNamespaceOption(literal: PsiElement): Boolean {
        val value = literal.parent as? JSLiteralExpression ?: return false
        val owner = (value.parent as? JSArrayLiteralExpression)?.let { it.parent } ?: value.parent
        return (owner as? JSProperty)?.name in NAMESPACE_OPTIONS
    }

    /** The JSX attribute [literal] is the value of, written as is or in an expression (`{cx('a', 'b')}`). */
    private fun attributeOf(literal: PsiElement): XmlAttribute? =
        ancestors(literal).firstNotNullOfOrNull { it as? XmlAttribute }

    /**
     * [literal]'s ancestors up to the function, tag or file holding it: a string in an event
     * handler (`onClick={() => alert('Saved')}`) is the handler's, not its attribute's.
     */
    private fun ancestors(literal: PsiElement): Sequence<PsiElement> =
        generateSequence(literal.parent) { it.parent }.takeWhile { it !is JSFunction && it !is XmlTag && it !is PsiFile }
}
