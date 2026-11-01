package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.parser.CodeNode

/**
 * What [RuleKeyExtractor] asks of a parsed file, whatever parsed it: the IDE's PSI in the plugin,
 * tree-sitter in `i18n-check`. Each parser answers in its own node types, so the rules of
 * [FrameworkRules] are read once, by one extractor, on both.
 *
 * Every question is asked of a [CodeNode] the caller already holds; none walks the whole file but
 * [imports], which an adapter is expected to cache per file.
 */
interface CodeGrammar {

    /** A call and the position of one of its arguments. */
    data class CallArgument(val call: CodeNode, val index: Int)

    /** An object literal and the name of one of its properties. */
    data class ObjectProperty(val owner: CodeNode, val name: String)

    /** A JSX attribute: the element holding it, that element's tag name — null when it is no plain name — and the attribute's name. */
    data class Attribute(val tag: CodeNode, val tagName: String?, val name: String)

    /** What a JSX attribute holds. */
    sealed interface AttributeValue {

        /** A quoted value: `context="male"`. */
        data class Text(val value: String) : AttributeValue

        /** A braced expression: `context={gender}`. */
        data class Expression(val node: CodeNode) : AttributeValue

        /** No value at all: `<Trans context>`. */
        data object Bare : AttributeValue
    }

    /** True for a quoted string literal; a template literal is none. */
    fun isStringLiteral(node: CodeNode): Boolean

    /** The text of the string literal [node] without its quotes. */
    fun stringValue(node: CodeNode): String

    /**
     * The call taking [node] as one of its arguments, with the argument's position. A node in a
     * branch of a ternary argument, `t(cond ? 'a' : 'b')`, or in parentheses, is that argument;
     * one in the ternary's condition is not. Null when [node] is no argument.
     */
    fun argumentOf(node: CodeNode): CallArgument?

    /** The arguments of [call], in order. */
    fun arguments(call: CodeNode): List<CodeNode>

    /** The function [call] calls, as written: `t`, `intl.formatMessage`, `this.props.t`. Null when it is no name or member path. */
    fun callee(call: CodeNode): String?

    /** The object literal holding [node] as one of its property values, with that property's name. Null for any other node. */
    fun propertyOf(node: CodeNode): ObjectProperty?

    /**
     * The value of the property [name] of the object literal [obj]: its value node, or the property
     * itself for a shorthand `{ context }`, which has none. Null when [obj] is no object literal or
     * has no such property.
     */
    fun property(obj: CodeNode?, name: String): CodeNode?

    /** The string literals [node] holds: its own text for a literal, those of its items for an array, nothing otherwise. */
    fun strings(node: CodeNode?): List<String>

    /** The module specifiers the file holding [node] imports or requires: `react-i18next`, `@lingui/core/macro`. */
    fun imports(node: CodeNode): Set<String>

    /** The JSX attribute [node] is the quoted value of, null for any other node. */
    fun attributeOf(node: CodeNode): Attribute?

    /** The text of the quoted attribute value [node], without its quotes. */
    fun attributeText(node: CodeNode): String

    /** What the attribute [name] of the JSX element [tag] holds, null when the element has no such attribute. */
    fun attribute(tag: CodeNode, name: String): AttributeValue?
}
