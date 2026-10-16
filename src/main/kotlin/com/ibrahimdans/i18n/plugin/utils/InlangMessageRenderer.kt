package com.ibrahimdans.i18n.plugin.utils

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What to show for a value of the inlang message format (Paraglide JS): itself when it is a plain
 * string, one branch of its variants when it is an array.
 *
 * ```json
 * "cart_items": [{ "declarations": ["input count", "local countPlural = count: plural"],
 *                  "selectors": ["countPlural"],
 *                  "match": { "countPlural=one": "One item.", "countPlural=other": "{count} items." } }]
 * ```
 *
 * The branch shown is the catch-all — every selector `*` or `other` — as ICU rendering picks
 * `other`; the first branch when there is none. An ellipsis marks that other branches exist. Text
 * that is no such array comes back unchanged, so any other format reads as before.
 *
 * Pure: no IntelliJ class.
 */
object InlangMessageRenderer {

    private const val ELLIPSIS = "…"
    private val CATCH_ALL = setOf("*", "other")

    /** [value] — a property value's source text, quoted or not — as it should be displayed. */
    fun preview(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[")) return value
        val variants = runCatching { JsonParser.parseString(trimmed) }.getOrNull() as? JsonArray ?: return value
        val match = variants.firstOrNull { it is JsonObject && it.get("match") is JsonObject }
            ?.asJsonObject?.getAsJsonObject("match") ?: return value
        val branches = match.entrySet().mapNotNull { (selector, text) ->
            if (text.isJsonPrimitive && text.asJsonPrimitive.isString) selector to text.asString else null
        }
        if (branches.isEmpty()) return value
        val shown = branches.firstOrNull { (selector, _) -> isCatchAll(selector) } ?: branches.first()
        return if (branches.size > 1) shown.second + ELLIPSIS else shown.second
    }

    /** `countPlural=*`, `platform=*, userGender=*`, `countPlural=other`: every selector matches anything. */
    private fun isCatchAll(selector: String): Boolean =
        selector.split(',').all { part -> part.substringAfter('=', "").trim() in CATCH_ALL }
}
