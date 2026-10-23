package com.ibrahimdans.i18n.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** How a [ProjectResult] is printed: [TEXT] for a reader, [JSON] for a tool. */
enum class Format {
    TEXT, JSON;

    fun render(result: ProjectResult, maxProblems: Int): String = when (this) {
        TEXT -> Report.text(result, maxProblems)
        JSON -> Report.json(result)
    }
}

object Report {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * For each module, one line per locale with its counts, its keys listed under it, and the unused
     * keys when the code was checked; then the unknown keys, and the total against the problems allowed:
     * ```
     * fr: 2 missing, 1 empty
     *   missing  common:actions.save
     * ```
     * A project of several modules heads each with its name.
     */
    fun text(result: ProjectResult, maxProblems: Int): String = buildString {
        for (module in result.modules) {
            val locales = "${module.reference} (${plural(module.locales.size, "locale")})"
            appendLine(
                when {
                    result.modules.size == 1 -> "Reference locale: $locales"
                    module.module == null -> "Outside the modules: reference locale $locales"
                    else -> "Module ${module.module}: reference locale $locales"
                },
            )
            appendLine()
            for (locale in module.locales) {
                val counts = listOf("missing" to locale.missing, "orphan" to locale.orphans, "empty" to locale.empty)
                    .filter { (_, keys) -> keys.isNotEmpty() }
                if (counts.isEmpty()) {
                    appendLine("${locale.locale}: no problem")
                    continue
                }
                appendLine("${locale.locale}: " + counts.joinToString { (label, keys) -> countOf(keys.size, label) })
                for ((label, keys) in counts) keys.forEach { appendLine("  ${label.padEnd(LABEL_WIDTH)}$it") }
            }
            module.unused?.let { unused ->
                appendLine()
                appendLine("Unused keys: ${unused.size.takeIf { it > 0 } ?: "none"}")
                unused.forEach { appendLine("  $it") }
            }
            appendLine()
        }
        result.unknown?.let { unknown ->
            appendLine("Unknown keys: ${unknown.size.takeIf { it > 0 } ?: "none"}")
            unknown.forEach { appendLine("  ${it.file}:${it.line}  ${it.key}") }
            appendLine()
        }
        append(if (result.problems == 0) "No problem found." else "${plural(result.problems, "problem")} found (allowed: $maxProblems).")
    }

    /**
     * The shape documented in the README, stable for the tools reading it:
     * `{"reference", "problems", "locales": [{"locale", "missing", "orphans", "empty"}]}`, with
     * `"unused"` and `"unknown": [{"key", "file", "line"}]` when the code was checked. A project of
     * several modules lists them instead: `{"problems", "modules": [{"module", "reference", "problems",
     * "locales", "unused"}], "unknown"}`.
     */
    fun json(result: ProjectResult): String {
        val root = result.modules.singleOrNull()?.let { moduleJson(it, named = false) } ?: JsonObject().apply {
            add("modules", JsonArray().apply { result.modules.forEach { add(moduleJson(it, named = true)) } })
        }
        root.addProperty("problems", result.problems)
        result.unknown?.let { unknown ->
            root.add("unknown", JsonArray().apply {
                unknown.forEach {
                    add(JsonObject().apply {
                        addProperty("key", it.key)
                        addProperty("file", it.file)
                        addProperty("line", it.line)
                    })
                }
            })
        }
        return gson.toJson(root)
    }

    private fun moduleJson(module: CheckResult, named: Boolean) = JsonObject().apply {
        if (named) addProperty("module", module.module)
        addProperty("reference", module.reference)
        addProperty("problems", module.problems)
        add("locales", JsonArray().apply {
            module.locales.forEach { locale ->
                add(JsonObject().apply {
                    addProperty("locale", locale.locale)
                    add("missing", array(locale.missing))
                    add("orphans", array(locale.orphans))
                    add("empty", array(locale.empty))
                })
            }
        })
        module.unused?.let { add("unused", array(it)) }
    }

    private const val LABEL_WIDTH = 9

    private fun array(keys: List<String>) = JsonArray().apply { keys.forEach(::add) }

    /** `2 orphans`, `1 missing`: only the nouns take an `s`. */
    private fun countOf(count: Int, label: String) = if (label == "orphan") plural(count, label) else "$count $label"

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"
}
