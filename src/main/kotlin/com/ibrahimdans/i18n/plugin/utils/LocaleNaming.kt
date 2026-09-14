package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.LocalizationSource

/**
 * The single rule deciding which part of a translation file's path carries its locale.
 *
 * Three consumers — the gutter icon tooltip, the Ctrl+hover popup and the tool window's
 * data loader — each held a private copy of the shape-only regex that #122 replaced with
 * the ISO-backed [LocalizationSourceService.looksLikeLocale]. The copies never got the
 * correction, so `src/api/common.json` was still loaded under the locale `api`. They now
 * all come through here, and the ISO validation itself stays where #122 put it.
 *
 * The order is **stem first, parent second**, and it is deliberate:
 *
 *  - `locales/en.json` → the stem `en` is the locale, and the parent (`locales`) is not one;
 *  - `locales/en/common.json` → the stem `common` is not a locale, so the parent `en` wins;
 *  - the two only compete when both look like locales (`en/fr.json`), where the file's own
 *    name is the more specific designation.
 *
 * The consumers used to disagree on this order — the gutter and the hint tried the stem
 * first, the loader tried the parent first — which mattered for the *fallback* rather than
 * for the order itself: when neither part is a locale, the stem is what gets returned.
 * On `src/api/common.json` that is `common`, the file's own name, rather than `api`, a
 * source folder that never described a language.
 */
internal fun LocalizationSource.localeLabel(): String {
    // A module template states the locale: it is not guessed then.
    locale?.let { return it }
    val stem = name.substringBeforeLast('.')
    return when {
        LocalizationSourceService.looksLikeLocale(stem) -> stem
        LocalizationSourceService.looksLikeLocale(parent) -> parent
        else -> stem
    }
}

/**
 * True when the file's own name is its locale (`locales/en.json`), the "one file per locale"
 * layout. Such a file holds no namespace: everything inside it is the project's default one.
 */
internal fun LocalizationSource.isLocaleNamedFile(): Boolean =
    if (locale != null) namespace == null
    else LocalizationSourceService.looksLikeLocale(name.substringBeforeLast('.'))

/**
 * False when neither the stem nor the parent directory looks like a locale, i.e. when
 * [localeLabel] had to fall back to the file's own name for lack of a better designation.
 *
 * That fallback exists so a file a [Config.translationsRoot] sweeps in without a locale
 * anywhere in its path (`i18n/locales/my_page.json`, no `{locale}` segment at all) still
 * surfaces its content instead of it vanishing silently — see [LocalizationSourceService].
 * It was never meant to make that file's own name usable *as* a locale: the per-locale grid
 * (tool window table/tree, stats, CSV export/import, *Sync Keys*) compares real locales
 * against each other, and mixing in a column named after a namespace reports every other
 * locale as missing that "locale" for every single key, real orphan or not.
 */
internal fun LocalizationSource.hasRecognizedLocale(): Boolean =
    locale != null || LocalizationSourceService.looksLikeLocale(localeLabel())
