package com.ibrahimdans.i18n.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** How a [CheckResult] is printed: [TEXT] for a reader, [JSON] for a tool. */
enum class Format {
    TEXT, JSON;

    fun render(result: CheckResult, maxProblems: Int): String = when (this) {
        TEXT -> Report.text(result, maxProblems)
        JSON -> Report.json(result)
    }
}

object Report {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * One line per locale with its counts, its keys listed under it, then the total against the
     * problems allowed:
     * ```
     * fr: 2 missing, 1 empty
     *   missing  common:actions.save
     * ```
     */
    fun text(result: CheckResult, maxProblems: Int): String = buildString {
        appendLine("Reference locale: ${result.reference} (${plural(result.locales.size, "locale")})")
        appendLine()
        for (locale in result.locales) {
            val counts = listOf("missing" to locale.missing, "orphan" to locale.orphans, "empty" to locale.empty)
                .filter { (_, keys) -> keys.isNotEmpty() }
            if (counts.isEmpty()) {
                appendLine("${locale.locale}: no problem")
                continue
            }
            appendLine("${locale.locale}: " + counts.joinToString { (label, keys) -> countOf(keys.size, label) })
            for ((label, keys) in counts) keys.forEach { appendLine("  ${label.padEnd(LABEL_WIDTH)}$it") }
        }
        appendLine()
        append(if (result.problems == 0) "No problem found." else "${plural(result.problems, "problem")} found (allowed: $maxProblems).")
    }

    /**
     * The shape documented in the README, stable for the tools reading it:
     * `{"reference", "problems", "locales": [{"locale", "missing", "orphans", "empty"}]}`.
     */
    fun json(result: CheckResult): String {
        val root = JsonObject()
        root.addProperty("reference", result.reference)
        root.addProperty("problems", result.problems)
        root.add("locales", JsonArray().apply {
            result.locales.forEach { locale ->
                add(JsonObject().apply {
                    addProperty("locale", locale.locale)
                    add("missing", array(locale.missing))
                    add("orphans", array(locale.orphans))
                    add("empty", array(locale.empty))
                })
            }
        })
        return gson.toJson(root)
    }

    private const val LABEL_WIDTH = 9

    private fun array(keys: List<String>) = JsonArray().apply { keys.forEach(::add) }

    /** `2 orphans`, `1 missing`: only the nouns take an `s`. */
    private fun countOf(count: Int, label: String) = if (label == "orphan") plural(count, label) else "$count $label"

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"
}
