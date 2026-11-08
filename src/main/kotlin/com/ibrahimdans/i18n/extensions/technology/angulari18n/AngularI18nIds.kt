package com.ibrahimdans.i18n.extensions.technology.angulari18n

/**
 * The custom ids of Angular's native i18n, read from text alone: the `@@id` of an `i18n` attribute's
 * metadata and of a `$localize` template's block. No PSI here, so the trunk reads them without the
 * JavaScript plugin; what an element names is the language's business ([com.ibrahimdans.i18n.Lang.metadataKeyOf]).
 */
object AngularI18nIds {

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
}
