package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * A key as written at a call site.
 *
 * [keyPrefix] is what the call site's translation hook prepends and the literal does not write:
 * react-i18next's `useTranslation('ns', { keyPrefix: 'a.b' })`, next-intl's `useTranslations('A.B')`.
 * It is tokenized like the key itself, so it follows the configured key separator.
 *
 * [unsplit] keys are read whole, never cut at a separator: a Laravel JSON phrase such as
 * `__('Saved.')` or `__('Hello, :name')` is the key of `lang/<locale>.json` as written.
 *
 * [sourceText] is true when the key is the message's own source text rather than an id chosen for
 * it: Lingui's `<Trans>Hello world!</Trans>`, catalogued under `Hello world!`.
 */
data class RawKey(
    val keyElements: List<KeyElement>,
    val arguments: List<String> = emptyList(),
    val keyPrefix: List<KeyElement> = emptyList(),
    val unsplit: Boolean = false,
    val sourceText: Boolean = false,
)
