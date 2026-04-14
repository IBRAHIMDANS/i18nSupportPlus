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
 * Simple: <Trans>Hello world!</Trans> → msgid "Hello world!"
 *
 * Interpolated <Trans> (e.g. <Trans>Hello {name}!</Trans>) is intentionally excluded:
 * reconstructing the ICU msgid across multiple children would produce a string that does
 * not match the XmlText element's own text, making CompositeKeyAnnotatorBase's
 * element.text.indexOf(fullKey.source) return -1 and produce an invalid TextRange.
 */
class LinguiTransKeyExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlText) return false
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return false
        if (tag.name != "Trans") return false
        val children = tag.value.children
        // Reject interpolated <Trans> — non-text children (JSX expressions) would require
        // cross-child reconstruction, producing a msgid that can't be located inside the
        // XmlText element's text and causing an invalid TextRange in the annotator.
        if (!children.all { it is XmlText }) return false
        // Guard against multiple XmlText children to avoid duplicate annotations
        return children.filterIsInstance<XmlText>().firstOrNull() == element
    }

    override fun extract(element: PsiElement): RawKey {
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return RawKey(emptyList())
        return RawKey(listOf(KeyElement.literal(buildMsgid(tag))))
    }

    /**
     * Assembles the Lingui msgid from the <Trans> tag value.
     * Only called for tags whose children are exclusively XmlText (no JSX expressions).
     */
    private fun buildMsgid(tag: XmlTag): String =
        tag.value.children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
}
