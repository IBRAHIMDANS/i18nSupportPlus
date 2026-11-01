package com.ibrahimdans.i18n.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The file holding [locale]'s keys spelled with [nsPrefix] (`common:`, or empty for the default namespace). */
data class LocaleFile(val locale: String, val nsPrefix: String, val path: Path)

/**
 * `--format github`: one GitHub Actions workflow command per problem, which GitHub shows as an
 * annotation on the file and line it names, then the text report for the log.
 *
 * - an unknown key on the line of the code calling it;
 * - a missing, orphan or empty key on line 1 of the locale's file holding its namespace — the readers
 *   keep no line — or with no file when the locale has none for it;
 * - an unused key on line 1 of the reference locale's file.
 *
 * The commands are `::error` while the problems exceed `--max-problems`, `::warning` when the check passes.
 * Paths are given from the working directory, the workspace in GitHub Actions.
 * See https://docs.github.com/actions/reference/workflows-and-actions/workflow-commands.
 */
object GithubReport {

    /** The environment variable naming the Markdown file GitHub shows on the run's summary page. */
    const val STEP_SUMMARY = "GITHUB_STEP_SUMMARY"

    fun render(result: ProjectResult, maxProblems: Int, project: Path, workspace: Path = Path.of("").toAbsolutePath()): String {
        val level = if (result.problems > maxProblems) "error" else "warning"
        val root = project.toAbsolutePath().normalize()
        fun relative(path: Path) = workspace.relativize(path).joinToString("/")
        val commands = buildList {
            for (module in result.modules) {
                val reference = module.reference
                for (locale in module.locales) {
                    fun fileOf(key: String) = module.fileOf(locale.locale, key)?.let(::relative)
                    locale.missing.forEach {
                        add(command(level, fileOf(it), 1, "Missing translation", "${locale.locale} lacks $it, which the reference locale $reference holds"))
                    }
                    locale.orphans.forEach {
                        add(command(level, fileOf(it), 1, "Orphan key", "${locale.locale} holds $it, which the reference locale $reference lacks"))
                    }
                    locale.empty.forEach {
                        add(command(level, fileOf(it), 1, "Empty translation", "$it is empty in ${locale.locale}"))
                    }
                }
                module.unused.orEmpty().forEach {
                    add(command(level, module.fileOf(reference, it)?.let(::relative), 1, "Unused key", "No code uses $it"))
                }
            }
            val reference = result.modules.singleOrNull()?.reference?.let { "the reference locale $it" } ?: "its reference locale"
            result.unknown.orEmpty().forEach {
                add(command(level, relative(root.resolve(it.file)), it.line, "Unknown key", "${it.key} is called here, but $reference has no such key"))
            }
        }
        return (commands + "" + Report.text(result, maxProblems)).joinToString("\n")
    }

    /**
     * Appends to the file [STEP_SUMMARY] names, when [env] defines it, a table of each locale's counts,
     * the unused and unknown keys when the code was checked, and the total against the problems allowed.
     */
    fun summarize(result: ProjectResult, maxProblems: Int, env: (String) -> String?) {
        val file = env(STEP_SUMMARY)?.takeIf { it.isNotBlank() } ?: return
        Files.writeString(Path.of(file), summary(result, maxProblems), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun summary(result: ProjectResult, maxProblems: Int): String = buildString {
        appendLine("### i18n-check")
        appendLine()
        for (module in result.modules) {
            if (result.modules.size > 1) {
                appendLine("#### ${module.module?.let { "Module $it" } ?: "Outside the modules"}")
                appendLine()
            }
            appendLine("| Locale | Missing | Orphans | Empty |")
            appendLine("| --- | ---: | ---: | ---: |")
            for (locale in module.locales) {
                val name = if (locale.locale == module.reference) "${locale.locale} (reference)" else locale.locale
                appendLine("| $name | ${locale.missing.size} | ${locale.orphans.size} | ${locale.empty.size} |")
            }
            appendLine()
            module.unused?.let { appendLine("Unused keys: ${it.size}"); appendLine() }
        }
        result.unknown?.let { appendLine("Unknown keys: ${it.size}"); appendLine() }
        val total = if (result.problems == 1) "1 problem" else "${result.problems} problems"
        appendLine(if (result.problems == 0) "No problem found." else "$total found (allowed: $maxProblems).")
    }

    /** `::error file=…,line=…,title=…::message`, the file and line left out when there is no file. */
    private fun command(level: String, file: String?, line: Int, title: String, message: String): String {
        val properties = listOfNotNull(
            file?.let { "file=${escapeProperty(it)}" },
            file?.let { "line=$line" },
            "title=${escapeProperty(title)}",
        )
        return "::$level ${properties.joinToString(",")}::${escapeData(message)}"
    }

    /** As `@actions/core` escapes a command's message. */
    internal fun escapeData(text: String) = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    /** As `@actions/core` escapes a command's property: the message's escapes, and `:` and `,`. */
    internal fun escapeProperty(text: String) = escapeData(text).replace(":", "%3A").replace(",", "%2C")

    /**
     * The file of [locale] holding [key]: the one whose namespace prefix starts the key, the longest
     * when several do (a namespace file over the locale's default one).
     */
    private fun CheckResult.fileOf(locale: String, key: String): Path? =
        files.filter { it.locale == locale && key.startsWith(it.nsPrefix) }.maxByOrNull { it.nsPrefix.length }?.path
}
