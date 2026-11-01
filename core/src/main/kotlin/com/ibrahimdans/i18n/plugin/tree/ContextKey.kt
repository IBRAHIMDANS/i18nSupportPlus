package com.ibrahimdans.i18n.plugin.tree

/**
 * The `context` option of an i18next call: `t('friend', { context: 'male' })` reads the key
 * `friend_male`, falling back to `friend` when the file lacks it.
 */
sealed interface ContextOption {

    /** `{ context: gender }`, `{ context }`: known at runtime only, so any variant may be read. */
    data object Dynamic : ContextOption

    /** `{ context: 'male' }`: the variant `key_male`, else the key itself. */
    data class Fixed(val value: String) : ContextOption
}

/**
 * How i18next spells a context variant, and how to get back from one to the key the code writes.
 *
 * A variant is the key, i18next's `contextSeparator` (`_`) and the context: `friend_male`. It takes
 * plural suffixes like any key (`friend_male_one`), stripped first ([PluralKey.stripSuffix]).
 *
 * A key read from a translation file says nothing of being a variant: `friend_male` may as well be
 * a key named so. [split] therefore answers for every key holding the separator, and its callers
 * only count a call site that does pass a `context` — the over-wide reading [PluralKey] makes too.
 */
object ContextKey {

    /** i18next's default `contextSeparator`. */
    const val SEPARATOR = "_"

    /** The key i18next reads for [base] under the context [value]: `friend_male`. */
    fun variant(base: String, value: String): String = base + SEPARATOR + value

    /**
     * The base and context of a key segment spelled as a variant — `friend` and `male` for
     * `friend_male` — or null when [segment] holds no separator between two non-empty parts.
     * [segment] is the last segment of a key, its plural suffix already stripped.
     */
    fun split(segment: String): Pair<String, String>? {
        val at = segment.lastIndexOf(SEPARATOR)
        if (at <= 0 || at + SEPARATOR.length >= segment.length) return null
        return segment.substring(0, at) to segment.substring(at + SEPARATOR.length)
    }

    /** True when [option] reads the variant of context [value]: always for a dynamic context. */
    fun reads(option: ContextOption?, value: String): Boolean = when (option) {
        null -> false
        ContextOption.Dynamic -> true
        is ContextOption.Fixed -> option.value == value
    }
}
