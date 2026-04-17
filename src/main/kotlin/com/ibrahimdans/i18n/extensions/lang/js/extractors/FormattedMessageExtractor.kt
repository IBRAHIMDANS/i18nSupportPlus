package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

class FormattedMessageExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlAttributeValue) return false
        val attribute = element.parent as? XmlAttribute ?: return false
        if (attribute.name != "id") return false
        val tag = attribute.parent
        return tag.name == "FormattedMessage"
    }

    override fun extract(element: PsiElement): RawKey {
        val value = (element as XmlAttributeValue).value
        return RawKey(listOf(KeyElement.literal(value)))
    }
}
