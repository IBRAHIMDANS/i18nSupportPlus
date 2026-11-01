package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleNames

/**
 * Flutter's ARB catalogues (`lib/l10n/app_en.arb`) read outside the IDE: JSON files whose name the
 * locale guess cannot place, `app_en` and `l10n` being no locale. The rule is the plugin's
 * `ArbCatalogues.localeOf`.
 */
object ArbCatalogue {

    const val EXTENSION = "arb"

    private const val LOCALE_PROPERTY = "@@locale"

    /** The catalogue [tree]'s `"@@locale": "fr"`, null when it states none. */
    fun declaredLocale(tree: Tree<ParsedNode>): String? =
        tree.findChild(LOCALE_PROPERTY)?.value()?.value?.takeIf { it.isNotBlank() }

    /**
     * The locale of a catalogue named [stem]: [declared] when it states one, as `flutter gen-l10n`
     * reads it, else the end of the name after an `_` (`app_en_US` → `en_US`), else the name itself
     * when it is a locale (`fr`); null when none is found.
     */
    fun localeOf(stem: String, declared: String?): String? {
        declared?.let { return it }
        return stem.indices.filter { stem[it] == '_' }.map { stem.substring(it + 1) }.firstOrNull(LocaleNames::looksLikeLocale)
            ?: stem.takeIf(LocaleNames::looksLikeLocale)
    }
}
