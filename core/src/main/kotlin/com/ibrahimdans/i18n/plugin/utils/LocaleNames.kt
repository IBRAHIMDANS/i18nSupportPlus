package com.ibrahimdans.i18n.plugin.utils

/** Recognises locale codes in file and directory names, from the JDK's ISO tables. */
object LocaleNames {

    // ISO 639-1 (2 letters), their ISO 639-2 equivalents ("fra", "deu"), and the
    // 3-letter languages known to the JDK's CLDR data ("fil", "haw"). The full
    // ISO 639-3 registry is deliberately not used: its ~7900 codes collide with
    // common directory names ("api" is Apiaká).
    private val ISO_LANGUAGES: Set<String> = buildSet {
        val twoLetter = java.util.Locale.getISOLanguages()
        addAll(twoLetter)
        twoLetter.forEach { code ->
            runCatching { add(java.util.Locale.of(code).isO3Language) }
        }
        java.util.Locale.getAvailableLocales().forEach { locale ->
            if (locale.language.isNotEmpty()) add(locale.language)
        }
    }

    private val ISO_COUNTRIES: Set<String> = java.util.Locale.getISOCountries().toSet()

    // ISO 15924 script codes actually seen in real locale tags (zh-Hans, sr-Latn, uz-Cyrl…).
    // The JDK exposes no registry for these — Character.UnicodeScript's names don't match the
    // four-letter codes — so, like ISO_LANGUAGES above, this is a deliberately curated subset
    // rather than the full ~200-entry registry: completeness would cost nothing here (an
    // obscure script code colliding with a real directory name is far less likely than an
    // obscure language code doing so), but a curated list documents which ones this plugin
    // has actually needed. Any 4-letter subtag not in this set is presumed not to be a
    // script — that used to be "any 4 letters", which let "my_page" pass as language "my"
    // (Burmese) plus script "Page" (#220).
    private val ISO_SCRIPTS: Set<String> = setOf(
        "Latn", "Cyrl", "Grek", "Arab", "Hebr", "Hans", "Hant", "Jpan", "Kore",
        "Deva", "Beng", "Guru", "Gujr", "Orya", "Taml", "Telu", "Knda", "Mlym",
        "Sinh", "Thai", "Laoo", "Mymr", "Khmr", "Tibt", "Mong", "Geor", "Armn",
        "Ethi", "Cher", "Cans", "Hang", "Bopo", "Hani", "Kana", "Hira", "Syrc",
        "Thaa", "Adlm", "Vaii", "Cham", "Tglg"
    )

    /**
     * True when [name] is a plausible locale code: an ISO language ("en", "fil"),
     * optionally followed by an ISO region ("pt-BR", "zh_CN") or an ISO 15924 script
     * ("sr-Latn"). Shape alone is not enough — "web", "ios" or "src" must not be mistaken
     * for languages, and neither must an arbitrary 4-letter word be mistaken for a script
     * (they both used to be).
     */
    fun looksLikeLocale(name: String): Boolean {
        val parts = name.split('-', '_')
        if (parts.isEmpty() || parts.size > 2) return false
        if (parts[0].lowercase() !in ISO_LANGUAGES) return false
        if (parts.size == 1) return true
        val subtag = parts[1]
        return subtag.uppercase() in ISO_COUNTRIES ||
            subtag.lowercase().replaceFirstChar { it.uppercase() } in ISO_SCRIPTS
    }
}
