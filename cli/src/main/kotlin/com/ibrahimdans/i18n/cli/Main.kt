package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.ide.settings.Config
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

/**
 * What the command line asks for. [project] is where `.idea/i18nSettings.xml` is looked for, and the
 * translation files too unless [locales] names their directory; [code] checks the keys the code uses.
 */
data class Options(
    val project: Path,
    val locales: Path?,
    val reference: String?,
    val format: Format,
    val maxProblems: Int,
    val code: Boolean = false,
) {
    companion object {
        /** The options of [args], or null when they ask for the help. */
        fun parse(args: List<String>): Options? {
            var project: Path? = null
            var locales: Path? = null
            var reference: String? = null
            var format = Format.TEXT
            var maxProblems = 0
            var code = false
            val rest = args.iterator()
            fun value(option: String): String =
                if (rest.hasNext()) rest.next() else throw UsageError("$option needs a value")
            while (rest.hasNext()) {
                when (val arg = rest.next()) {
                    "-h", "--help" -> return null
                    "--locales" -> locales = Path.of(value(arg))
                    "--reference" -> reference = value(arg)
                    "--code" -> code = true
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
            if (code && locales != null) throw UsageError("--code checks a whole project: drop --locales")
            return Options(project ?: Path.of("."), locales, reference, format, maxProblems, code)
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
        Usage: i18n-check [project] [--locales <dir> | --code] [--reference <locale>] [--format text|json] [--max-problems N]

          project              the project checked, where .idea/i18nSettings.xml is read from (default: the current directory)
          --locales <dir>      only check the directory holding <locale>.json|yml or <locale>/<namespace>.json|yml
          --code               also report the keys the i18next code never uses, and those it uses but the reference lacks
          --reference <locale> the locale the others are compared to (default: the module's or the settings', then en)
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
            warnUnreadLocales(options, err)
            out.println(options.format.render(result, options.maxProblems))
            if (result.problems > options.maxProblems) PROBLEMS else OK
        } catch (e: CannotCheck) {
            err.println("i18n-check: ${e.message}")
            CANNOT_CHECK
        }
    }

    /** A `gb/` folder beside `en/` and `fr/` is no locale: said once per path, on the error stream. */
    private fun warnUnreadLocales(options: Options, err: PrintStream) {
        val config = runCatching { SettingsFile.read(options.project) }.getOrNull() ?: return
        val root = options.locales ?: options.project
        for (path in UnreadLocales.find(root, config)) {
            val name = path.substringAfterLast('/').substringBefore('.')
            err.println("i18n-check: $path not checked, '$name' is a country code, not a language code; to check it, alias it: \"localeAliases\": \"$name=<locale>\"")
        }
    }

    private fun check(options: Options): ProjectResult {
        val config = try {
            SettingsFile.read(options.project)
        } catch (e: Exception) {
            throw CannotCheck("cannot read ${e.message}")
        }
        val scopes = scopesOf(options, config)
        val results = scopes.map { scope ->
            val labels = scope.translations.values.flatMap { it.keys }.distinct().sorted()
            // `--reference en` finds `en-US` as the settings' locale does.
            val wanted = options.reference ?: ReferenceLocale.wanted(scope.module, config)
            val reference = LocaleMatching.pick(wanted, labels)
                ?: throw CannotCheck("reference locale $wanted not found among ${labels.joinToString()}" + scope.module?.let { " in module ${it.name}" }.orEmpty())
            val result = TranslationCheck.run(scope.translations, reference, config::aliasedLocale)
            result.copy(
                locales = result.locales.filter { it.locale == reference || !config.ignoresLocale(it.locale) },
                module = scope.module?.name,
            )
        }
        if (!options.code) return ProjectResult(results)
        val code = CodeUsages(options.project, config)
        val referenceKeys = scopes.zip(results) { scope, result ->
            scope.translations.filterValues { result.reference in it }.keys
        }
        return ProjectResult(
            results.zip(referenceKeys) { result, keys -> result.copy(unused = code.unused(keys)) },
            code.unknown(referenceKeys.flatten()),
        )
    }

    /** The translations [options] point to: the `--locales` directory, or every module of the project. */
    private fun scopesOf(options: Options, config: Config): List<Scope> {
        val locales = options.locales
        if (locales == null) {
            if (!Files.isDirectory(options.project)) throw CannotCheck("no such directory: ${options.project}")
            return ProjectSources.read(options.project, config).ifEmpty {
                throw CannotCheck("no translation file in ${options.project}: expected <locale>.json|yml or <locale>/<namespace>.json|yml")
            }
        }
        if (!Files.isDirectory(locales)) throw CannotCheck("no such directory: $locales")
        val translations = LocaleDirectory.read(locales, config)
        if (translations.isEmpty()) {
            throw CannotCheck("no translation file in $locales: expected <locale>.json|yml or <locale>/<namespace>.json|yml")
        }
        return listOf(Scope(null, translations))
    }
}
