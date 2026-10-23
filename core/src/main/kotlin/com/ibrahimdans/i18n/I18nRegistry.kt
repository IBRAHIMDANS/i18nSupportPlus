package com.ibrahimdans.i18n

/**
 * The translation formats and technologies known: the plugin reads them from its extension points,
 * a test or a command-line tool lists its own in a [StaticI18nRegistry].
 *
 * [N] and [F] are those of the [TranslationTechnology]s held — a PSI element and a `Localization` in
 * the plugin. Both are covariant, so a registry of simple technologies ([Nothing]) is one of any.
 */
interface I18nRegistry<out N, out F : TranslationFormat> {
    fun formats(): List<F>
    fun technologies(): List<TranslationTechnology<N, F>>
}

/** A registry of fixed lists, held in memory. */
class StaticI18nRegistry<out N, out F : TranslationFormat>(
    private val formats: List<F> = emptyList(),
    private val technologies: List<TranslationTechnology<N, F>> = emptyList(),
) : I18nRegistry<N, F> {
    override fun formats(): List<F> = formats
    override fun technologies(): List<TranslationTechnology<N, F>> = technologies
}
