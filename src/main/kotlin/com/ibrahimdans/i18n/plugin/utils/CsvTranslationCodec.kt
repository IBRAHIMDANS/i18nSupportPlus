package com.ibrahimdans.i18n.plugin.utils

/**
 * Pure CSV codec and import-plan computation for translation export/import.
 *
 * Format: first column is the full key ("ns:path.to.key" or "path.to.key"),
 * one column per locale. RFC 4180 escaping: fields containing commas, quotes
 * or newlines are quoted, embedded quotes are doubled.
 *
 * No IntelliJ dependency on purpose — everything here is unit-testable.
 */
object CsvTranslationCodec {

    const val KEY_COLUMN = "key"

    /** One value to write during import: [key] in [locale] becomes [value]. */
    data class ImportEntry(val key: String, val locale: String, val value: String, val isCreation: Boolean)

    /**
     * What an import would do, computed by diffing the CSV against the project.
     * Unknown keys and unknown locale columns are reported, never written:
     * a translator's typo must not create keys silently.
     */
    data class ImportPlan(
        val entries: List<ImportEntry>,
        val ignoredKeys: List<String>,
        val ignoredColumns: List<String>,
    )

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes [translations] (key → locale → value) as CSV with the header
     * `key,<locales...>`. Rows are sorted by key for stable diffs.
     */
    fun encode(locales: List<String>, translations: Map<String, Map<String, String>>): String {
        val header = listOf(KEY_COLUMN) + locales
        val rows = translations.keys.sorted().map { key ->
            listOf(key) + locales.map { locale -> translations[key]?.get(locale) ?: "" }
        }
        return (listOf(header) + rows).joinToString("\r\n") { row ->
            row.joinToString(",") { escape(it) }
        }
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + field.replace("\"", "\"\"") + "\""
        else field

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses CSV [text] into records. Handles quoted fields containing commas,
     * doubled quotes and newlines. Accepts both \r\n and \n line endings.
     *
     * @throws IllegalArgumentException on malformed input (unterminated quote,
     *         or a stray quote in the middle of an unquoted field).
     */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() { record.add(field.toString()); field.clear() }
        fun endRecord() { endField(); records.add(record.toList()); record.clear() }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> {
                    require(field.isEmpty()) { PluginBundle.message("csv.error.quote.inside.field", i) }
                    inQuotes = true
                }
                c == ',' -> endField()
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { endRecord(); i++ }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        require(!inQuotes) { PluginBundle.message("csv.error.unterminated.quote") }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()

        // Drop trailing fully-empty records (file ending with a newline).
        return records.filterNot { rec -> rec.all { it.isEmpty() } }
    }

    // ── Import plan ───────────────────────────────────────────────────────────

    /**
     * Diffs parsed CSV [records] against the project's [existing] translations
     * (key → locale → value) and [knownLocales].
     *
     * Rules:
     *  - header must start with the "key" column;
     *  - a column whose name is not a known locale is ignored and reported;
     *  - a row whose key does not exist in the project is ignored and reported;
     *  - an empty CSV cell means "no change" (never erases a translation);
     *  - a cell equal to the current value is a no-op;
     *  - otherwise the entry is planned, flagged as creation when the locale
     *    currently has no value for that key.
     *
     * @throws IllegalArgumentException when the header is missing or lacks the key column.
     */
    fun computeImportPlan(
        existing: Map<String, Map<String, String>>,
        knownLocales: List<String>,
        records: List<List<String>>,
    ): ImportPlan {
        require(records.isNotEmpty()) { PluginBundle.message("csv.error.no.header") }
        val header = records.first()
        require(header.firstOrNull() == KEY_COLUMN) {
            PluginBundle.message("csv.error.first.column", KEY_COLUMN, header.firstOrNull() ?: "")
        }

        val localeColumns = header.drop(1)
        val ignoredColumns = localeColumns.filterNot { it in knownLocales }

        val entries = mutableListOf<ImportEntry>()
        val ignoredKeys = mutableListOf<String>()

        for (record in records.drop(1)) {
            val key = record.firstOrNull().orEmpty()
            if (key.isEmpty()) continue
            val currentValues = existing[key]
            if (currentValues == null) {
                ignoredKeys.add(key)
                continue
            }
            localeColumns.forEachIndexed { idx, locale ->
                if (locale !in knownLocales) return@forEachIndexed
                val value = record.getOrNull(idx + 1).orEmpty()
                if (value.isEmpty()) return@forEachIndexed
                val current = currentValues[locale]
                if (value != current) {
                    entries.add(ImportEntry(key, locale, value, isCreation = current.isNullOrEmpty()))
                }
            }
        }
        return ImportPlan(entries, ignoredKeys, ignoredColumns)
    }
}
