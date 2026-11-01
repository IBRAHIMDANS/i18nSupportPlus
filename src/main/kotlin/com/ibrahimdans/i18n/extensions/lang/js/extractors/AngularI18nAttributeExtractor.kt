package com.ibrahimdans.i18n.extensions.lang.js.extractors

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

    private const val ID_MARKER = "@@"

    /** The id of the metadata [text] — `i18n="site header|An introduction@@introductionHeader"` — or null. */
    fun idOf(text: String): String? {
        val id = text.substringAfter(ID_MARKER, "").trim()
        return id.takeIf { it.isNotEmpty() && id.none(Char::isWhitespace) }
    }

    /**
     * The id of a `$localize` template's metadata block, from the template's text as written:
     * `` `:meaning|description@@id:Welcome ${name}` ``. Null without a block or without an id.
     */
    fun localizeIdOf(templateText: String): String? {
        val text = templateText.removePrefix("`")
        if (!text.startsWith(":")) return null
        val end = text.indexOf(':', startIndex = 1).takeIf { it > 0 } ?: return null
        return idOf(text.substring(1, end))
    }

    /** Whether [name] is an Angular i18n attribute: `i18n`, or `i18n-title` and its kind for an attribute. */
    fun isI18nAttribute(name: String): Boolean = name == "i18n" || name.startsWith("i18n-")

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
        if (!AngularI18nMetadata.isI18nAttribute(attribute.name)) return null
        return AngularI18nMetadata.idOf(value.value)
    }
}
