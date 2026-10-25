package com.ibrahimdans.i18n.extract.angular

import com.ibrahimdans.i18n.extract.i18next.ARGUMENTS
import com.ibrahimdans.i18n.extract.i18next.CALL_EXPRESSION
import com.ibrahimdans.i18n.extract.i18next.calleeText
import com.ibrahimdans.i18n.extract.i18next.isQuotedString
import com.ibrahimdans.i18n.extract.i18next.items
import com.ibrahimdans.i18n.extract.i18next.unquoted
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * Transloco's calls, over a template read by [AngularTemplate] or a TypeScript file — the plugin's
 * `TranslocoPipeExtractor`, `TranslocoDirectiveExtractor`, `TranslocoReadExtractor` and `TranslocoExtractor`.
 */
fun translocoExtractors(): List<CodeKeyExtractor> = listOf(
    AngularPipeKeyExtractor(setOf("transloco")),
    AngularDirectiveKeyExtractor(AngularTemplate.TRANSLOCO_DIRECTIVE),
    TranslocoReadKeyExtractor(),
    ServiceCallKeyExtractor(TRANSLOCO_METHODS, "transloco", standalone = TRANSLOCO_FUNCTIONS),
)

/** ngx-translate's calls — the plugin's `AngularTranslatePipeExtractor`, `NgxTranslateDirectiveExtractor` and `NgxTranslateExtractor`. */
fun ngxTranslateExtractors(): List<CodeKeyExtractor> = listOf(
    AngularPipeKeyExtractor(setOf("translate")),
    AngularDirectiveKeyExtractor(AngularTemplate.TRANSLATE_DIRECTIVE, notKeys = setOf("yes", "no")),
    ServiceCallKeyExtractor(setOf("instant", "get", "stream"), "translate"),
)

private val TRANSLOCO_METHODS = setOf("translate", "selectTranslate")
private val TRANSLOCO_FUNCTIONS = setOf("translate", "translateSignal")
private val TRANSLOCO_IMPORT = Regex("""from\s*['"]@(?:jsverse|ngneat)/transloco['"]""")

private const val BINARY_EXPRESSION = "binary_expression"
private const val MEMBER_EXPRESSION = "member_expression"
private const val STATEMENT_BLOCK = "statement_block"
private const val EXPRESSION_STATEMENT = "expression_statement"
private const val IDENTIFIER = "identifier"

/** The call taking [node] as its first argument, or null. */
private fun callTaking(node: CodeNode): CodeNode? {
    val arguments = node.parent?.takeIf { it.type == ARGUMENTS && it.items().firstOrNull() == node } ?: return null
    return arguments.parent?.takeIf { it.type == CALL_EXPRESSION }
}

private fun literalKey(node: CodeNode): RawKey = RawKey(listOf(KeyElement.literal(node.unquoted().trim())))

/** `'key' | transloco`, the template's pipe read by the grammar as a binary `|` whose right operand names it. */
class AngularPipeKeyExtractor(private val pipes: Set<String>) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val pipe = node.parent?.takeIf { it.type == BINARY_EXPRESSION && it.children.firstOrNull() == node } ?: return null
        if (pipe.children.getOrNull(1)?.text != "|") return null
        val name = pipe.children.getOrNull(2)?.takeIf { it.type == IDENTIFIER }?.text ?: return null
        return if (name in pipes) literalKey(node) else null
    }
}

/**
 * The key of an attribute directive, rewritten by [AngularTemplate] into a call of [function]:
 * `transloco="key"`, `[translate]="'key'"`, `<p translate>key</p>`. A value holding an interpolation, or
 * one of [notKeys] — HTML's own `translate="no"` — is none.
 */
class AngularDirectiveKeyExtractor(private val function: String, private val notKeys: Set<String> = emptySet()) : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        if (callTaking(node)?.calleeText() != function) return null
        val key = node.unquoted().trim()
        return if (key.isEmpty() || "{{" in key || key in notKeys) null else literalKey(node)
    }
}

/**
 * `t('title')` under `*transloco="let t; read: 'admin'"`: the key `admin.title`. The innermost block
 * [AngularTemplate] opened for a directive publishing the function called decides; one without a
 * prefix gives the key as written. A call under no such block is left to the other extractors.
 *
 * The prefix is a key prefix whatever its first segment: the keys of a scope file are spelled with the
 * scope as their first segment (`admin.title`), as the code writes them.
 */
class TranslocoReadKeyExtractor : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val call = callTaking(node) ?: return null
        val function = call.children.firstOrNull()?.takeIf { it.type == IDENTIFIER }?.text ?: return null
        val read = generateSequence(call.parent) { it.parent }
            .filter { it.type == STATEMENT_BLOCK }
            .firstNotNullOfOrNull { block -> readOf(block, function) } ?: return null
        val key = listOf(KeyElement.literal(node.unquoted()))
        return RawKey(key, keyPrefix = if (read.isEmpty()) emptyList() else listOf(KeyElement.literal(read)))
    }

    /** The prefix [block] gives [function], from its first statement `$tlr('admin', 't')`; null when it publishes no such function. */
    private fun readOf(block: CodeNode, function: String): String? {
        val statement = block.items().firstOrNull()?.takeIf { it.type == EXPRESSION_STATEMENT } ?: return null
        val marker = statement.children.firstOrNull()?.takeIf { it.type == CALL_EXPRESSION && it.calleeText() == AngularTemplate.TRANSLOCO_READ }
            ?: return null
        val (read, name) = marker.children.lastOrNull { it.type == ARGUMENTS }?.items().orEmpty().map { it.unquoted() }
            .takeIf { it.size == 2 } ?: return null
        return read.takeIf { name == function }
    }
}

/**
 * A service method taking the key: `this.translocoService.translate('key')`, `translate.instant('key')`.
 * The qualifier must name the service — hold [service], in any case: `translate` alone is too common a
 * method name to claim. The [standalone] functions are read called bare, in a file importing Transloco.
 */
class ServiceCallKeyExtractor(
    private val methods: Set<String>,
    private val service: String,
    private val standalone: Set<String> = emptySet(),
) : CodeKeyExtractor {

    @Volatile
    private var imports: Pair<CodeNode, Boolean>? = null

    override fun canExtract(node: CodeNode): Boolean = extract(node) != null

    override fun extract(node: CodeNode): RawKey? {
        if (!node.isQuotedString()) return null
        val callee = callTaking(node)?.children?.firstOrNull() ?: return null
        val claimed = when (callee.type) {
            MEMBER_EXPRESSION -> {
                val qualifier = callee.children.firstOrNull()?.text.orEmpty()
                callee.children.lastOrNull()?.text in methods && qualifier.contains(service, ignoreCase = true)
            }
            IDENTIFIER -> callee.text in standalone && importsTransloco(node)
            else -> false
        }
        return if (claimed) literalKey(node) else null
    }

    private fun importsTransloco(node: CodeNode): Boolean {
        val root = generateSequence(node) { it.parent }.last()
        imports?.takeIf { it.first == root }?.let { return it.second }
        return TRANSLOCO_IMPORT.containsMatchIn(root.text).also { imports = root to it }
    }
}
