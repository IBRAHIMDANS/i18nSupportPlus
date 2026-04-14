package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Extracts i18n key from Lingui <Trans>source text</Trans> — the source text IS the msgid.
 *
 * Unlike key-based approaches (i18n._('key')), Lingui's source-based JSX component uses
 * the visible text directly as the lookup key in .po files.
 *
 * Example: <Trans>Hello world!</Trans> → msgid "Hello world!"
 */
class LinguiTransKeyExtractor : KeyExtractor {

    private fun isPlainTextTransTag(tag: XmlTag): Boolean {
        return tag.name == "Trans" && tag.value.children.all { it is XmlText }
    }

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlText) return false
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return false
        return isPlainTextTransTag(tag)
    }

    override fun extract(element: PsiElement): RawKey {
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java) ?: return RawKey(emptyList())
        if (!isPlainTextTransTag(tag)) return RawKey(emptyList())
        val msgid = tag.value.textElements.joinToString("") { it.text }.trim()
        return RawKey(listOf(KeyElement.literal(msgid)))
    }
}
