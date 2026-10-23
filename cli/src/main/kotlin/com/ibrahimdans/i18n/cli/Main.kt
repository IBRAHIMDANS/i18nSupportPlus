package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(I18nCheck.run(args.toList(), System.out, System.err))
}

/** A command line the check cannot run: printed with the usage. */
class UsageError(message: String) : Exception(message)

/** Something on disk keeps the check from running: no translations, an unknown locale, a file it cannot parse. */
class CannotCheck(message: String) : Exception(message)

/** What the command line asks for. [project] is where `.idea/i18nSettings.xml` is looked for. */
data class Options(
    val project: Path,
    val locales: Path,
    val reference: String?,
    val format: Format,
    val maxProblems: Int,
) {
    companion object {
        /** The options of [args], or null when they ask for the help. */
        fun parse(args: List<String>): Options? {
            var project: Path? = null
            var locales: Path? = null
            var reference: String? = null
            var format = Format.TEXT
            var maxProblems = 0
            val rest = args.iterator()
            fun value(option: String): String =
                if (rest.hasNext()) rest.next() else throw UsageError("$option needs a value")
            while (rest.hasNext()) {
                when (val arg = rest.next()) {
                    "-h", "--help" -> return null
                    "--locales" -> locales = Path.of(value(arg))
                    "--reference" -> reference = value(arg)
                    "--format" -> format = value(arg).let { name ->
                        Format.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                            ?: throw UsageError("unknown format '$name', expected text or json")
                    }
                    "--max-problems" -> maxProblems = value(arg).let { count ->
                        count.toIntOrNull()?.takeIf { it >= 0 } ?: throw UsageError("--max-problems expects a number >= 0, not '$count'")
                    }
                    else -> when {
                        arg.startsWith("-") -> throw UsageError("unknown option $arg")
                        project != null -> throw UsageError("only one project directory, got $project and $arg")
                        else -> project = Path.of(arg)
                    }
                }
            }
            return Options(project ?: Path.of("."), locales ?: throw UsageError("--locales is required"), reference, format, maxProblems)
        }
    }
}

/**
 * `i18n-check`: compares the translation files of a project to its reference locale.
 *
 * Exits with [OK] when the problems found do not exceed `--max-problems`, [PROBLEMS] when they do,
 * and [CANNOT_CHECK] when the command line is wrong or there is nothing it can check.
 */
object I18nCheck {

    const val OK = 0
    const val PROBLEMS = 1
    const val CANNOT_CHECK = 2

    val USAGE = """
        Usage: i18n-check [project] --locales <dir> [--reference <locale>] [--format text|json] [--max-problems N]

          project              where .idea/i18nSettings.xml is read from (default: the current directory)
          --locales <dir>      the directory holding <locale>.json|yml or <locale>/<namespace>.json|yml
          --reference <locale> the locale the others are compared to (default: the settings', then en)
          --format text|json   the report's format (default: text)
          --max-problems N     the problems allowed before exiting with 1 (default: 0)
    """.trimIndent()

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = try {
            Options.parse(args) ?: return OK.also { out.println(USAGE) }
        } catch (e: UsageError) {
            err.println("i18n-check: ${e.message}")
            err.println(USAGE)
            return CANNOT_CHECK
        }
        return try {
            val result = check(options)
            out.println(options.format.render(result, options.maxProblems))
            if (result.problems > options.maxProblems) PROBLEMS else OK
        } catch (e: CannotCheck) {
            err.println("i18n-check: ${e.message}")
            CANNOT_CHECK
        }
    }

    private fun check(options: Options): CheckResult {
        if (!Files.isDirectory(options.locales)) throw CannotCheck("no such directory: ${options.locales}")
        val config = try {
            SettingsFile.read(options.project)
        } catch (e: Exception) {
            throw CannotCheck("cannot read ${options.project.resolve(SettingsFile.PATH)}: ${e.message}")
        }
        val translations = LocaleDirectory.read(options.locales, config)
        val labels = translations.values.flatMap { it.keys }.distinct().sorted()
        if (labels.isEmpty()) {
            throw CannotCheck("no translation file in ${options.locales}: expected <locale>.json|yml or <locale>/<namespace>.json|yml")
        }
        // `--reference en` finds `en-US` as the settings' locale does.
        val wanted = options.reference ?: ReferenceLocale.wanted(null, config)
        val reference = LocaleMatching.pick(wanted, labels)
            ?: throw CannotCheck("reference locale $wanted not found among ${labels.joinToString()}")
        return TranslationCheck.run(translations, reference)
    }
}
