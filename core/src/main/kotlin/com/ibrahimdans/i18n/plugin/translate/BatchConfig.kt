package com.ibrahimdans.i18n.plugin.translate

/**
 * The batch form of an engine: [body] takes `{texts}` — the JSON array of the texts — or
 * `{textsJson}`, the same array escaped for inside a JSON string (an LLM prompt), besides the other
 * placeholders. The translations are the array at [listPath], each one the string at [itemField] of
 * its element (the element itself when blank). [jsonText] says the value at [listPath] is a string
 * holding that array — an LLM's answer. At most [maxSize] texts go in one request.
 */
data class BatchConfig(
    val body: String,
    val listPath: String,
    val itemField: String = "",
    val jsonText: Boolean = false,
    val maxSize: Int
)
