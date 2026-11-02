package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlToken

/**
 * A template syntax whose markup holds texts and interpolations side by side — an Angular
 * template's `Hello {{ user.name }}!`, a Svelte component's `Hello {name}!` — as the parser
 * leaves them: the text of a tag split around each interpolation into sibling [XmlText]s.
 */
internal interface MarkupDialect {

    /** Whether the file of [element] is written in this dialect. */
    fun applies(element: PsiElement): Boolean

    /** The element holding an interpolation's expression: `{{ name }}` without its braces when they are siblings. */
    fun isInterpolation(element: PsiElement): Boolean

    /** A brace of an interpolation written beside its expression, Angular's `{{` and `}}`: part of the message's range. */
    fun isInterpolationBrace(element: PsiElement): Boolean = false

    /** A token of the markup that is neither text nor tag, a block's braces: left out of the message. */
    fun isIgnorable(element: PsiElement): Boolean = false

    /** How a message of this dialect's library writes the variable [name]. */
    fun placeholder(name: String): String

    /** Whether the text of [tag] is no message: a key a directive reads, or a text another tool translates. */
    fun holdsNoMessage(tag: XmlTag): Boolean = false

    /** Whether [attribute] of [tag] is translated by another tool already. */
    fun translatesAttribute(tag: XmlTag, attribute: XmlAttribute): Boolean = false

    /**
     * An interpolation the markup holds inside a text rather than beside it: a Blade echo
     * `{{ $name }}`, which the view's HTML keeps as a template element of its [XmlText]: a variable of
     * the message, like an interpolation beside the text.
     */
    fun isInnerInterpolation(element: PsiElement): Boolean = false

    /** The expression the interpolation [element] holds, trimmed: its JavaScript expression by default. */
    fun expressionOf(element: PsiElement): String? = PsiTreeUtil.findChildOfType(element, JSExpression::class.java)?.text?.trim()

    /** The variable a message names [expression] by: the last segment of a plain path (`name` for `user.name`), else null. */
    fun variableNameOf(expression: String): String? =
        if (MarkupTexts.PLAIN_PATH.matches(expression)) expression.substringAfterLast('.') else null
}

/**
 * A text of a template's markup to translate, as [MarkupTexts] reads it: the whole content of its
 * tag — texts and interpolations — reported on the [first] of its texts, or one attribute value.
 */
internal sealed class MarkupText {
    abstract val element: PsiElement
    abstract val text: String
    abstract val range: TextRange

    /** The content of a tag, texts and interpolations: [variables] are the interpolations, in order. */
    class Content(override val element: XmlText, override val text: String, override val range: TextRange, val variables: List<MessageVariable>) :
        MarkupText()

    /** A visible attribute's value: the call replaces the whole [attribute], bound in the dialect's syntax. */
    class Attribute(override val element: XmlAttributeValue, override val text: String, override val range: TextRange, val attribute: XmlAttribute) :
        MarkupText()
}

/**
 * What the hard-coded text inspections of a template's markup report, by the rules of
 * [HardcodedTextRules], shared by Angular templates and Svelte components:
 *  - a tag's content is one message when it holds only texts and interpolations of a plain path
 *    (`name`, `user.name`), each one a variable; a child tag, a block, a comment or any other
 *    expression (a call, a pipe) keeps the texts of that tag silent — `you` in
 *    `<p>Hi <b>you</b></p>` is reported, `Hi` is not;
 *  - an attribute is one a user reads ([HardcodedTextRules.VISIBLE_ATTRIBUTES]) holding a plain
 *    value: a bound one (`[title]`, `title={x}`) and an interpolated one (`title="{{ x }}"`) are not;
 *  - the text holds a letter once its placeholders and entities are removed;
 *  - no tag around it holds code ([HardcodedTextRules.CODE_TAGS]).
 */
internal object MarkupTexts {

    internal val PLAIN_PATH = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*""")
    private val WHITESPACE = Regex("\\s+")

    /** The text [element] — an [XmlText] or an [XmlAttributeValue] — stands for, when [dialect] reports it; else null. */
    fun of(element: PsiElement, dialect: MarkupDialect): MarkupText? {
        // An Angular component's inline template is an injected fragment of the dialect: read like a file.
        if (!dialect.applies(element)) return null
        return when (element) {
            is XmlText -> content(element, dialect)
            is XmlAttributeValue -> attribute(element, dialect)
            else -> null
        }
    }

    private fun content(text: XmlText, dialect: MarkupDialect): MarkupText.Content? {
        val container = text.parent ?: return null
        val tag = PsiTreeUtil.getParentOfType(text, XmlTag::class.java)
        if (tag != null && (!isOutsideCode(tag) || dialect.holdsNoMessage(tag))) return null
        val parts = mutableListOf<PsiElement>()
        var first: PsiElement? = null
        var last: PsiElement? = null
        for (child in generateSequence(container.firstChild) { it.nextSibling }) {
            val inRange = when {
                child is XmlText || dialect.isInterpolation(child) -> { parts += child; true }
                dialect.isInterpolationBrace(child) -> true
                child is PsiWhiteSpace || child is XmlToken || child is XmlAttribute || dialect.isIgnorable(child) -> false
                // A child tag, a block, a comment: the texts around it are no message on their own.
                else -> return null
            }
            if (inRange) {
                if (first == null) first = child
                last = child
            }
        }
        // Reported once, on the tag's first text: `{{ count }} items` starts with an interpolation.
        if (parts.firstOrNull { it is XmlText } !== text) return null
        val variables = linkedMapOf<String, String>()
        val message = StringBuilder()
        // The texts alone, without the interpolations: what tells a message from punctuation around a value.
        val bare = StringBuilder()
        var holdsVariables = false
        for (part in parts) {
            if (part is XmlText) {
                if (part.children.none(dialect::isInnerInterpolation)) { message.append(part.text); bare.append(part.text); continue }
                holdsVariables = true
                for (child in part.children) {
                    if (!dialect.isInnerInterpolation(child)) { message.append(child.text); bare.append(child.text); continue }
                    val name = variable(child, dialect, variables) ?: return null
                    // The blanks after an echo belong to its element: `{{ $count }} items`.
                    message.append(dialect.placeholder(name)).append(child.text.substring(child.text.trimEnd().length))
                }
                continue
            }
            val name = variable(part, dialect, variables) ?: return null
            holdsVariables = true
            message.append(dialect.placeholder(name))
        }
        val value = StringUtil.unescapeXmlEntities(message.toString()).replace(WHITESPACE, " ").trim()
        if (!HardcodedTextRules.isTranslatable(value)) return null
        // A placeholder no rule strips, Laravel's `:name`, holds letters of its own.
        if (holdsVariables && !HardcodedTextRules.isTranslatable(StringUtil.unescapeXmlEntities(bare.toString()))) return null
        val range = trimmed(TextRange(first!!.textRange.startOffset, last!!.textRange.endOffset), text.containingFile.text)
        return MarkupText.Content(text, value, range, variables.map { (name, expression) -> MessageVariable(name, expression, dialect.placeholder(name)) })
    }

    /** The variable the interpolation [element] stands for, recorded in [variables]; null when it is no plain value, or a name two expressions share. */
    private fun variable(element: PsiElement, dialect: MarkupDialect, variables: MutableMap<String, String>): String? {
        val expression = dialect.expressionOf(element) ?: return null
        val name = dialect.variableNameOf(expression) ?: return null
        val previous = variables.putIfAbsent(name, expression)
        return if (previous != null && previous != expression) null else name
    }

    private fun attribute(value: XmlAttributeValue, dialect: MarkupDialect): MarkupText.Attribute? {
        val attribute = value.parent as? XmlAttribute ?: return null
        val tag = attribute.parent ?: return null
        if (attribute.name !in HardcodedTextRules.VISIBLE_ATTRIBUTES || !isOutsideCode(tag)) return null
        if (dialect.translatesAttribute(tag, attribute)) return null
        // A plain value is a single token: a bound or interpolated one holds an expression.
        if (value.children.any { it !is XmlToken } || "{" in value.value) return null
        val text = StringUtil.unescapeXmlEntities(value.value).trim()
        if (!HardcodedTextRules.isTranslatable(text)) return null
        return MarkupText.Attribute(value, text, attribute.textRange, attribute)
    }

    private fun isOutsideCode(tag: XmlTag): Boolean =
        generateSequence(tag) { it.parentTag }.none { it.name.lowercase() in HardcodedTextRules.CODE_TAGS }

    /** [range] of [fileText] without the blanks at its ends. */
    private fun trimmed(range: TextRange, fileText: String): TextRange {
        var start = range.startOffset
        var end = range.endOffset
        while (start < end && fileText[start].isWhitespace()) start++
        while (end > start && fileText[end - 1].isWhitespace()) end--
        return TextRange(start, end)
    }
}
