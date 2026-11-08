package com.ibrahimdans.i18n.plugin.translate

/**
 * The value of [byLocale] for [target], which may come rewritten for its engine (`FR`, `EN-US`): the
 * locale written alike, else the bare language, else another variant of it.
 */
internal fun <V> valueForLocale(byLocale: Map<String, V>, target: String): V? {
    val wanted = normalizeLocale(target)
    val language = wanted.substringBefore('-')
    val normalized = byLocale.entries.associate { normalizeLocale(it.key) to it.value }
    return normalized[wanted] ?: normalized[language] ?: normalized.entries.firstOrNull { it.key.substringBefore('-') == language }?.value
}

private fun normalizeLocale(locale: String): String = locale.trim().lowercase().replace('_', '-')
