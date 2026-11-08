package com.ibrahimdans.i18n.extensions.lang.js.extractors

import com.ibrahimdans.i18n.extensions.technology.angulari18n.AngularI18nIds
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * The custom id of an Angular i18n message, read from its metadata: `meaning|description@@id`.
 *
 * Only a custom id names a key: a message without one is catalogued under an id `ng extract-i18n`
 * computes from its text, which the code does not write.
 */
object AngularI18nMetadata {

    private val EXTRACTORS = listOf(AngularI18nAttributeExtractor(), LocalizeTaggedTemplateExtractor())

    /**
     * The custom id [element] names when it is an Angular marker — an `i18n` attribute's value, a
     * `$localize` template — or null: what a search for a key's usages compares, the element's text
     * holding the metadata around it (`:@@home.title:Welcome`).
     */
    fun keyOf(element: PsiElement): String? =
        EXTRACTORS.firstOrNull { it.canExtract(element) }?.extract(element)?.keyElements?.joinToString("") { it.text }
}

/**
 * The custom id of an Angular template's i18n marker: `<h1 i18n="@@home.title">`, and the markers of
 * translated attributes, `<img i18n-alt="@@logo.alt" alt="Logo">`. The id is one key, never split at its dots.
 */
class AngularI18nAttributeExtractor : KeyExtractor {

    override fun canExtract(element: PsiElement): Boolean = idOf(element) != null

    override fun extract(element: PsiElement): RawKey = RawKey(listOf(KeyElement.literal(idOf(element).orEmpty())), unsplit = true)

    private fun idOf(element: PsiElement): String? {
        val value = element as? XmlAttributeValue ?: return null
        val attribute = value.parent as? XmlAttribute ?: return null
        if (!AngularI18nIds.isI18nAttribute(attribute.name)) return null
        return AngularI18nIds.idOf(value.value)
    }
}
