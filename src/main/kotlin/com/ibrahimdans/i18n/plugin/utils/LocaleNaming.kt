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
    LocalizationSourceService.looksLikeLocale(name.substringBeforeLast('.'))
