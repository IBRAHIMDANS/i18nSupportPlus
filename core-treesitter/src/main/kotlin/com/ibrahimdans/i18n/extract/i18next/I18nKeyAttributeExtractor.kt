package com.ibrahimdans.i18n.extract.i18next

import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The key of a JSX `i18nKey` attribute, react-i18next's `<Trans i18nKey="ns:a.b">`, on a tree-sitter
 * TSX tree: the string value, with no namespace — the plugin's `XmlAttributeKeyExtractor`, which
 * reads the attribute whatever its tag.
 */
class I18nKeyAttributeExtractor : CodeKeyExtractor {

    override fun canExtract(node: CodeNode): Boolean =
        node.isQuotedString() && node.parent?.takeIf { it.type == JSX_ATTRIBUTE }?.children?.firstOrNull()?.text == I18N_KEY

    override fun extract(node: CodeNode): RawKey? =
        if (canExtract(node)) RawKey(listOf(KeyElement.literal(node.unquoted()))) else null

    private companion object {
        const val I18N_KEY = "i18nKey"
    }
}
