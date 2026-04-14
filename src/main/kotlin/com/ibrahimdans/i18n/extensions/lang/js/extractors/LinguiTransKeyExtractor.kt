package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Extracts msgid from Lingui <Trans>source text</Trans> — the source text IS the key.
 *
 * Unlike key-based approaches (i18n._('key')), Lingui's source-based JSX component uses
 * the visible text directly as the lookup key in .po files.
 *
 * Simple:      <Trans>Hello world!</Trans>     → msgid "Hello world!"
 * Interpolated:<Trans>Hello {name}!</Trans>    → msgid "Hello {0}!" (ICU positional)
 *
 * Only triggers on the first XmlText child of <Trans> to avoid duplicate annotations
 * when the tag contains embedded JSX expressions.
 */
class LinguiTransKeyExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlText) return false
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return false
        if (tag.name != "Trans") return false
        // Only the first XmlText child triggers extraction to avoid duplicate annotations
        // when <Trans> contains embedded expressions (e.g. <Trans>Hello {name}!</Trans>)
        return tag.value.children.filterIsInstance<XmlText>().firstOrNull() == element
    }

    override fun extract(element: PsiElement): RawKey {
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(buildMsgid(tag))))
    }

    /**
     * Assembles the Lingui msgid from all children of the <Trans> tag value.
     * Non-text children (JSX expressions) are replaced with positional ICU placeholders {0}, {1}, …
     */
    private fun buildMsgid(tag: XmlTag): String {
        var expressionIndex = 0
        val sb = StringBuilder()
        for (child in tag.value.children) {
            if (child is XmlText) {
                sb.append(child.text)
            } else {
                sb.append("{$expressionIndex}")
                expressionIndex++
            }
        }
        return sb.toString().trim()
    }
}
