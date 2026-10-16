package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a Paraglide (inlang) project's `project.inlang/settings.json` says about its message files:
 *
 * ```json
 * { "baseLocale": "en", "locales": ["en", "fr"],
 *   "plugin.inlang.messageFormat": { "pathPattern": "./messages/{locale}.json" } }
 * ```
 *
 * `pathPattern` is a string or an array of them, relative to the folder holding `project.inlang`,
 * and names the locale `{locale}` — or `{languageTag}`, its older spelling, as `languageTags` and
 * `sourceLanguageTag` are the older names of `locales` and `baseLocale`. Only the message-format
 * plugin's patterns are read: another plugin (i18next's) writes files in its own format.
 *
 * Pure: no IntelliJ class, so it is tested without a platform fixture.
 */
data class InlangProjectSettings(
    val baseLocale: String?,
    val locales: List<String>,
    val pathPatterns: List<String>,
) {

    /**
     * Each locale's message files, as paths relative to the folder holding `project.inlang`:
     * `./messages/{locale}.json` gives `messages/fr.json` for `fr`.
     */
    fun messageFiles(): List<Pair<String, String>> = locales.flatMap { locale ->
        pathPatterns.map { pattern ->
            locale to pattern.replace(LOCALE, locale).replace(LANGUAGE_TAG, locale).removePrefix("./").trimStart('/')
        }
    }

    companion object {
        private const val LOCALE = "{locale}"
        private const val LANGUAGE_TAG = "{languageTag}"
        private const val MESSAGE_FORMAT = "plugin.inlang.messageFormat"

        /** The settings [text] holds, or null when it is no JSON object, or names no locale. */
        fun parse(text: String): InlangProjectSettings? {
            val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return null
            // `languageTags` / `sourceLanguageTag`: the names of inlang's first settings format.
            val locales = strings(root.get("locales")).ifEmpty { strings(root.get("languageTags")) }
            if (locales.isEmpty()) return null
            val baseLocale = (root.get("baseLocale") ?: root.get("sourceLanguageTag"))?.stringOrNull()
            val patterns = (root.get(MESSAGE_FORMAT) as? JsonObject)?.get("pathPattern")?.let(::strings).orEmpty()
            return InlangProjectSettings(baseLocale, locales, patterns)
        }

        /** A string or an array of strings, as a list; anything else is empty. */
        private fun strings(element: JsonElement?): List<String> = when {
            element == null -> emptyList()
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.stringOrNull() }
            else -> listOfNotNull(element.stringOrNull())
        }.filter { it.isNotBlank() }

        private fun JsonElement.stringOrNull(): String? =
            if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    }
}
