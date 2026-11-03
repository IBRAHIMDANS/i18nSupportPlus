package com.ibrahimdans.i18n.extract.angular

/**
 * The messages Angular's own i18n (`@angular/localize`) marks in code, read from the text as written: the
 * `i18n` and `i18n-<attribute>` markers of a template, and the `$localize` templates of a script. The plugin's
 * `AngularI18nMetadata`, without the platform.
 *
 * A message names a key only through its custom id, `meaning|description@@id`: one without is catalogued
 * under an id `ng extract-i18n` computes from its text ([isComputedId]), which the code does not write.
 */
object AngularMessageIds {

    /** A marked message: its custom [id], null for a message Angular computes the id of, at [offset] of the text read. */
    data class Message(val id: String?, val offset: Int)

    private const val ID_MARKER = "@@"

    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** `i18n`, `i18n-title`, valued or not; not `i18nPlural`, a pipe. */
    private val MARKER = Regex("""(?<![\w.\-\[(])i18n(?:-[\w.-]+)?(?:\s*=\s*(?:"([^"]*)"|'([^']*)'))?(?=[\s/>])""")

    /** `` $localize`:meaning|description@@id:text` ``: the metadata block opens the template. */
    private val LOCALIZE = Regex("""\${'$'}localize\s*`(?::([^:`]*):)?""")

    /** A component's inline template, `template: '…'`, in any quote. */
    private val INLINE_TEMPLATE = Regex("""\btemplate\s*:\s*(`|'|")(.*?)(?<!\\)\1""", RegexOption.DOT_MATCHES_ALL)

    /** The decimal id of `computeMsgId`, or the legacy SHA-1 one: what `ng extract-i18n` writes for a message without a custom id. */
    private val COMPUTED_ID = Regex("""\d+|[0-9a-f]{40}""")

    /** The id of the metadata [text] — `site header|An introduction@@introductionHeader` — or null. */
    fun idOf(text: String): String? {
        val id = text.substringAfter(ID_MARKER, "").trim()
        return id.takeIf { it.isNotEmpty() && id.none(Char::isWhitespace) }
    }

    /** True when [id] has the shape of an id `ng extract-i18n` computes, rather than one written in the code. */
    fun isComputedId(id: String): Boolean = COMPUTED_ID.matches(id)

    /** The messages of the template [text]: its markers inside a tag, comments left out. */
    fun ofTemplate(text: String): List<Message> = markersOf(text, 0)

    /**
     * The messages of the script [text]: its `$localize` templates, and the markers of a component's inline
     * `template:`.
     */
    fun ofScript(text: String): List<Message> {
        val localized = LOCALIZE.findAll(text).map { match ->
            Message(match.groups[1]?.value?.let(::idOf), match.range.first)
        }
        val inline = INLINE_TEMPLATE.findAll(text).flatMap { match ->
            val body = match.groups[2]!!
            markersOf(body.value, body.range.first)
        }
        return (localized + inline).sortedBy { it.offset }.toList()
    }

    private fun markersOf(text: String, base: Int): List<Message> {
        val comments = COMMENT.findAll(text).map { it.range }.toList()
        return MARKER.findAll(text)
            .filter { match -> comments.none { match.range.first in it } && insideTag(text, match.range.first) }
            .map { match ->
                val value = match.groups[1]?.value ?: match.groups[2]?.value
                Message(value?.let(::idOf), base + match.range.first)
            }
            .toList()
    }

    /** True when [offset] sits between a tag's `<` and its `>`. */
    private fun insideTag(text: String, offset: Int): Boolean {
        val open = text.lastIndexOf('<', offset)
        return open >= 0 && text.lastIndexOf('>', offset) < open && text.getOrNull(open + 1)?.isLetter() == true
    }
}
