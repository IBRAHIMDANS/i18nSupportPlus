package com.ibrahimdans.i18n.extensions.technology.symfony

/**
 * What the name of a Symfony translation file says: `messages+intl-icu.fr.yaml` holds the `messages`
 * domain in `fr`. Symfony cuts the name at its first two dots — domain, locale, format — so a domain
 * holds no dot; the `+intl-icu` suffix only says the messages are written in ICU MessageFormat, and
 * the domain is the same as without it.
 */
internal data class SymfonyCatalogue(val domain: String, val locale: String) {
    companion object {
        /** The domain a call or a template uses when it names none. */
        const val DEFAULT_DOMAIN = "messages"

        private const val ICU_SUFFIX = "+intl-icu"

        /** The catalogue [fileName] holds, or null when it is not named `<domain>.<locale>.<format>`. */
        fun parse(fileName: String): SymfonyCatalogue? {
            val parts = fileName.split('.', limit = 3)
            if (parts.size != 3 || parts.any { it.isEmpty() }) return null
            val domain = parts[0].removeSuffix(ICU_SUFFIX).takeIf { it.isNotEmpty() } ?: return null
            return SymfonyCatalogue(domain, parts[1])
        }
    }
}
