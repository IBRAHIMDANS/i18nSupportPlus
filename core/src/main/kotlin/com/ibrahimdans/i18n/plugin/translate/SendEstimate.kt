package com.ibrahimdans.i18n.plugin.translate

/**
 * What a machine translation pass is about to send, counted before its first call: per target
 * locale, the number of texts and their characters.
 *
 * A text is counted as the engine receives it: placeholders, tags and ICU syntax masked
 * ([PlaceholderMask]). Neither the context nor an LLM's prompt are counted, so the volume an engine
 * bills is somewhat higher. Characters are Unicode code points, as DeepL's quota counts them.
 *
 * Pure: no platform, no network.
 */
data class SendEstimate(val byLocale: Map<String, Volume>) {

    /** The [texts] sent for one locale and their [characters]. */
    data class Volume(val texts: Int, val characters: Int)

    val texts: Int get() = byLocale.values.sumOf { it.texts }

    val characters: Int get() = byLocale.values.sumOf { it.characters }

    /** Whether the pass is large enough to ask before sending it: a few texts go without a click. */
    fun needsConfirmation(): Boolean = texts >= CONFIRM_FROM_TEXTS

    companion object {
        /** From this many texts, the pass asks before sending; under it, it sends at once. */
        const val CONFIRM_FROM_TEXTS = 20

        /** The estimate of [sent], `target locale -> texts to translate`, a locale with none left out. */
        fun of(sent: Map<String, List<String>>): SendEstimate = SendEstimate(
            sent.filterValues { it.isNotEmpty() }.mapValues { (_, texts) ->
                Volume(texts.size, texts.sumOf { text -> PlaceholderMask.mask(text).text.let { it.codePointCount(0, it.length) } })
            }
        )
    }
}
