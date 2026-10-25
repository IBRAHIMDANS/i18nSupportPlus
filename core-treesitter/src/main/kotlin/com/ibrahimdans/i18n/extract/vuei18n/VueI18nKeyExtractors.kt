package com.ibrahimdans.i18n.extract.vuei18n

import com.ibrahimdans.i18n.extract.i18next.ARGUMENTS
import com.ibrahimdans.i18n.extract.i18next.CALL_EXPRESSION
import com.ibrahimdans.i18n.extract.i18next.TEMPLATE_STRING
import com.ibrahimdans.i18n.extract.i18next.TemplateCallKeyExtractor
import com.ibrahimdans.i18n.extract.i18next.TranslationCallKeyExtractor
import com.ibrahimdans.i18n.extract.i18next.calleeText
import com.ibrahimdans.i18n.extract.i18next.isQuotedString
import com.ibrahimdans.i18n.extract.i18next.items
import com.ibrahimdans.i18n.extract.i18next.translationCallOf
import com.ibrahimdans.i18n.extract.i18next.unquoted
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/** The functions `VueI18nTechnology` publishes: the global `$t` of templates, `$tc` and `$te`. */
val VUE_I18N_FUNCTIONS: Set<String> = setOf("\$t", "\$tc", "\$te")

/**
 * The vue-i18n extractors, over a component read by [VueSfc] or a plain script: `$t('key')` and its
 * template literal, the `t` / `te` of `useI18n()`, and the `v-t` / `keypath` markup.
 */
fun vueI18nExtractors(): List<CodeKeyExtractor> = listOf(
    VueI18nMarkupKeyExtractor(),
    VueI18nComposableKeyExtractor(),
    TranslationCallKeyExtractor(VUE_I18N_FUNCTIONS),
    TemplateCallKeyExtractor(VUE_I18N_FUNCTIONS),
)

/**
 * vue-i18n's markup, rewritten by [VueSfc] into a call of [VueSfc.MARKUP_CALL]: `v-t="'home.title'"`,
 * the `path` of `v-t="{ path: 'home.greeting', args: { … } }"`, and `<i18n-t keypath="…">`.
 *
 * The plugin's `VueTDirectiveExtractor` and `VueI18nKeypathExtractor`: only a literal that is the
 * whole value, or the `path` of an object that is, holds a key — the strings of `args` do not.
 */
class VueI18nMarkupKeyExtractor : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val parent = node.parent ?: return null
        val value = if (parent.type == PAIR && parent.children.lastOrNull() == node && parent.children.firstOrNull()?.text == PATH) {
            parent.parent?.takeIf { it.type == OBJECT }
        } else {
            node
        } ?: return null
        val arguments = value.parent?.takeIf { it.type == ARGUMENTS && it.items().firstOrNull() == value } ?: return null
        if (arguments.parent?.takeIf { it.type == CALL_EXPRESSION }?.calleeText() != VueSfc.MARKUP_CALL) return null
        return RawKey(listOf(KeyElement.literal(node.unquoted())))
    }

    private companion object {
        const val PAIR = "pair"
        const val OBJECT = "object"
        const val PATH = "path"
    }
}

/**
 * The Composition API's `t('key')` and `te('key')`, in a file destructuring that name from
 * `useI18n()` — the plugin's `VueI18nComposableExtractor`: a `t` of another origin stays out.
 */
class VueI18nComposableKeyExtractor : CodeKeyExtractor {

    /** The file last read and the names it takes from `useI18n()`. */
    @Volatile
    private var composables: Pair<CodeNode, Set<String>>? = null

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        val template = node.type == TEMPLATE_STRING
        if (!template && !node.isQuotedString()) return null
        val call = translationCallOf(node, FUNCTIONS) ?: return null
        val root = generateSequence(call) { it.parent }.last()
        val names = composables?.takeIf { it.first == root }?.second
            ?: FUNCTIONS.filterTo(mutableSetOf()) { DESTRUCTURING.getValue(it).containsMatchIn(root.text) }.also { composables = root to it }
        if (call.calleeText() !in names) return null
        // A key built at runtime, `t(`order.status.${s}`)`, is read as the template of `$t`.
        return if (template) TEMPLATES.extract(node) else RawKey(listOf(KeyElement.literal(node.unquoted())))
    }

    private companion object {
        val FUNCTIONS = setOf("t", "te")
        val TEMPLATES = TemplateCallKeyExtractor(FUNCTIONS)
        val DESTRUCTURING = FUNCTIONS.associateWith { name -> Regex("""\{[^}]*\b$name\b[^}]*}\s*=\s*useI18n\s*\(""") }
    }
}
