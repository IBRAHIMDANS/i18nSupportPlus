package com.ibrahimdans.i18n.plugin.utils

/**
 * Which of a project's locales a setting names.
 *
 * Folding, inlay hints, hover and Ctrl+click each compared their locale setting to the files'
 * locale labels with `==`. A project laid out as `en-GB` / `fr-FR` under the default setting
 * `en` therefore showed nothing at all — no folding, no hint, no hover value — and so did a
 * setting spelled `en_gb` against a folder `en-GB`, without a word about why.
 */
object LocaleMatching {

    private fun normalize(locale: String): String = locale.trim().lowercase().replace('_', '-')

    /**
     * The label among [labels] that [wanted] designates, or null when none does:
     *  1. the same locale, case and `-`/`_` aside (`en_gb` is `en-GB`);
     *  2. failing that, when [wanted] is a language alone, one of its regional variants — the
     *     one whose region repeats the language first (`fr-FR`, `es-ES`), then alphabetically;
     *  3. never the other way round: `en-GB` does not take `en-US`.
     */
    fun pick(wanted: String, labels: Collection<String>): String? {
        val target = normalize(wanted)
        if (target.isEmpty()) return null
        labels.firstOrNull { normalize(it) == target }?.let { return it }
        if ('-' in target) return null
        return labels
            .filter { normalize(it).let { label -> '-' in label && label.substringBefore('-') == target } }
            .sortedWith(compareBy({ normalize(it) != "$target-$target" }, { normalize(it) }))
            .firstOrNull()
    }
}
