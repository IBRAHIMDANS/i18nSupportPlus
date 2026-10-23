package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey

/** A key found in source: the node holding it and the key as written there. */
data class SourceKey(val node: CodeNode, val key: RawKey)

/** The i18next / react-i18next extractors, reading calls to [functionNames]. */
fun i18nextExtractors(functionNames: Set<String> = I18NEXT_FUNCTIONS): List<CodeKeyExtractor> = listOf(
    TranslationCallKeyExtractor(functionNames),
    TemplateCallKeyExtractor(functionNames),
    I18nKeyAttributeExtractor(),
)

/** Every key under [root], in source order: each node is read by the first of [extractors] that reads it. */
fun findKeys(root: CodeNode, extractors: List<CodeKeyExtractor> = i18nextExtractors()): List<SourceKey> {
    val keys = mutableListOf<SourceKey>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        extractors.firstNotNullOfOrNull { it.extract(node) }?.let { keys += SourceKey(node, it) }
        pending.addAll(0, node.children)
    }
    return keys
}
