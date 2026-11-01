package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The keys a framework's [FrameworkRules] describe, read from any parser through its [CodeGrammar]:
 * the one extractor behind the plugin's react-intl extractors and `i18n-check`'s react-intl and
 * Lingui ones.
 *
 * A string literal is a key when a [CallRule] places it: the argument of a call to the rule's
 * function, the key property of a descriptor passed to it, or of each descriptor of a catalogue —
 * the call matched on its callee, and read only when the file imports one of the rule's packages.
 * The options object after the key gives the namespaces and context the rule names. A quoted
 * attribute value is a key when a [ComponentRule] places it on its tag.
 *
 * Template literals and hooks are not read here: the i18next extractors of each side do, with the
 * names [FrameworkPresets] gives them.
 */
class RuleKeyExtractor(private val rules: FrameworkRules, private val grammar: CodeGrammar) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? = callKey(node) ?: componentKey(node)

    /**
     * True for a string literal held by a descriptor one of the rules reads — `formatMessage({ id,
     * defaultMessage })` — whichever property it is: the key is the `id`, and the other strings of
     * the descriptor are source text, which a generic extraction of the call's first argument must
     * not report as keys.
     */
    fun isInsideDescriptor(node: CodeNode): Boolean {
        if (!grammar.isStringLiteral(node)) return false
        val owner = grammar.propertyOf(node)?.owner ?: return false
        return rules.calls.any { rule -> descriptorCall(owner, rule) != null }
    }

    private fun callKey(node: CodeNode): RawKey? {
        if (!grammar.isStringLiteral(node)) return null
        for (rule in rules.calls) {
            val (call, keyIndex) = callReading(node, rule) ?: continue
            val options = keyIndex?.let { grammar.arguments(call).getOrNull(it + 1) }
            return RawKey(
                listOf(KeyElement.literal(grammar.stringValue(node))),
                rule.namespaceOption?.let { grammar.strings(grammar.property(options, it)) }.orEmpty(),
                context = rule.contextOption?.let { contextOf(grammar.property(options, it)) },
            )
        }
        return null
    }

    /** The call [rule] reads [node] as the key of, with the position of the key argument — none for a catalogue — or null. */
    private fun callReading(node: CodeNode, rule: CallRule): Pair<CodeNode, Int?>? {
        val argument = when (val location = rule.key) {
            is KeyLocation.Argument -> grammar.argumentOf(node)?.takeIf { it.index == location.index }
            is KeyLocation.Descriptor, is KeyLocation.Catalogue -> {
                val owner = grammar.propertyOf(node)?.takeIf { it.name == keyProperty(location) }?.owner ?: return null
                descriptorCall(owner, rule)
            }
        } ?: return null
        if (!calleeMatches(argument.call, rule)) return null
        if (rule.importedFrom.isNotEmpty() && !importsOneOf(node, rule.importedFrom)) return null
        return argument.call to argument.index.takeIf { rule.key !is KeyLocation.Catalogue }
    }

    /** The call [rule] takes the object literal [owner] as a descriptor of, or null — the callee not checked yet. */
    private fun descriptorCall(owner: CodeNode, rule: CallRule): CodeGrammar.CallArgument? = when (val location = rule.key) {
        is KeyLocation.Argument -> null
        is KeyLocation.Descriptor -> grammar.argumentOf(owner)?.takeIf { it.index == location.index }
        // The descriptor is the argument itself, `defineMessage({ id })`, or one level below it, `defineMessages({ a: { id } })`.
        is KeyLocation.Catalogue -> (grammar.argumentOf(owner) ?: grammar.propertyOf(owner)?.let { grammar.argumentOf(it.owner) })?.takeIf { it.index == 0 }
    }?.takeIf { calleeMatches(it.call, rule) }

    private fun keyProperty(location: KeyLocation): String? = when (location) {
        is KeyLocation.Argument -> null
        is KeyLocation.Descriptor -> location.property
        is KeyLocation.Catalogue -> location.property
    }

    private fun calleeMatches(call: CodeNode, rule: CallRule): Boolean {
        val callee = grammar.callee(call) ?: return false
        return when (rule.match) {
            CalleeMatch.EXACT -> callee == rule.callee
            CalleeMatch.SUFFIX -> callee == rule.callee || callee.endsWith(".${rule.callee}")
        }
    }

    private fun importsOneOf(node: CodeNode, packages: List<String>): Boolean =
        grammar.imports(node).any { specifier -> packages.any { specifier.startsWith(it) } }

    /** A quoted string names the context; the shorthand `{ context }` and any other expression are only known at runtime. */
    private fun contextOf(value: CodeNode?): ContextOption? = when {
        value == null -> null
        grammar.isStringLiteral(value) -> ContextOption.Fixed(grammar.stringValue(value))
        else -> ContextOption.Dynamic
    }

    private fun componentKey(node: CodeNode): RawKey? {
        val attribute = grammar.attributeOf(node) ?: return null
        val rule = rules.components.firstOrNull { it.keyAttribute == attribute.name && (it.tag == null || it.tag == attribute.tagName) } ?: return null
        return RawKey(
            listOf(KeyElement.literal(grammar.attributeText(node))),
            context = rule.contextAttribute?.let { contextOf(attribute.tag, it) },
        )
    }

    /** `context="male"` and `context={'male'}` name their variant, a bare attribute or any other expression is only known at runtime. */
    private fun contextOf(tag: CodeNode, attribute: String): ContextOption? = when (val value = grammar.attribute(tag, attribute)) {
        null -> null
        is CodeGrammar.AttributeValue.Text -> ContextOption.Fixed(value.value)
        is CodeGrammar.AttributeValue.Expression -> contextOf(value.node)
        CodeGrammar.AttributeValue.Bare -> ContextOption.Dynamic
    }
}
