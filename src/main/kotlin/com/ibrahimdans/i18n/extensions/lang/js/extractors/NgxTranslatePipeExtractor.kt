package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

private fun PsiElement.containsTranslatePipe(): Boolean =
    parent?.text?.contains("| translate") == true

class NgxTranslatePipeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean {
        if (element !is XmlAttributeValue && element !is XmlText) return false
        return element.containsTranslatePipe()
    }

    override fun extract(element: PsiElement): RawKey {
        val raw = element.text.trim().removeSurrounding("\"").removeSurrounding("'")
        val key = raw.substringBefore("|").trim()
        return RawKey(listOf(KeyElement.literal(key)))
    }
}
