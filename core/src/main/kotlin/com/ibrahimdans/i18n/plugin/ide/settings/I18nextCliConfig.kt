package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * What an i18next-cli configuration file (`i18next.config.ts`) states, as far as the setup wizard
 * can use it: the project already wrote down where its catalogues go and how its keys are split.
 * `next-i18next.config.js`, `lingui.config.*`, `transloco.config.*` and the `backend.loadPath` of an
 * i18next init state the same things, and are read into the same shape.
 *
 * Only literal values are kept: the file is TypeScript, read without running Node, so a value
 * computed at runtime — `output: (lng, ns) => …`, `keySeparator: SEPARATOR` — is left null, as is
 * `false` (no separator, no default namespace), which no setting of the plugin can hold.
 *
 * `locales` is not read: the plugin finds the locales in the catalogues themselves, and has no
 * setting listing them.
 *
 * Parser-free, like the rest of the wizard's rules: the IDE reads the file into this shape, and
 * [WizardPlan] turns it into proposals without knowing how it was read.
 *
 * @param file the configuration file, project-relative: the origin shown for every value
 * @param output where the catalogues are, e.g. `public/locales/{{language}}/{{namespace}}.json`
 * @param referenceLocale the locale the others are translated from, when the file states one
 * @param fromProjectRoot [output] is project-relative rather than relative to [file]: a `loadPath`
 */
data class I18nextCliConfig(
    val file: String,
    val output: String? = null,
    val defaultNs: String? = null,
    val keySeparator: String? = null,
    val nsSeparator: String? = null,
    val referenceLocale: String? = null,
    val fromProjectRoot: Boolean = false,
) {

    /** [output] in `/`, without its leading `./`; null for an absolute path. */
    private val segments: List<String>?
        get() = output?.replace('\\', '/')?.removePrefix("./")?.takeUnless { it.startsWith("/") }?.split('/')

    /**
     * The folder [output] writes into, project-relative: its path up to the first segment holding
     * a placeholder, resolved from the configuration file's folder, which i18next-cli runs from.
     * Null when nothing fixed comes before the placeholders, or for an absolute path.
     */
    val translationsRoot: String?
        get() {
            val segments = segments ?: return null
            val fixed = segments.takeWhile { !it.contains(PLACEHOLDER) }
            // The last segment is the file name when no placeholder is met: `locales/en.json`.
            val folders = if (fixed.size == segments.size) fixed.dropLast(1) else fixed
            val root = folders.filter { it.isNotEmpty() && it != "." }.joinToString("/")
            if (root.isEmpty()) return null
            val base = if (fromProjectRoot) "" else file.replace('\\', '/').substringBeforeLast('/', "")
            return if (base.isEmpty()) root else "$base/$root"
        }

    /**
     * What follows [translationsRoot] in [output], in the plugin's own placeholders:
     * `{{lng}}/{{ns}}.json` is `{lang}/{ns}.json`. Null without a placeholder, or with one the
     * plugin has no equivalent for.
     */
    val pathTemplate: String?
        get() {
            val segments = segments ?: return null
            val first = segments.indexOfFirst { it.contains(PLACEHOLDER) }
            if (first < 0) return null
            val template = PLACEHOLDERS.entries.fold(segments.drop(first).joinToString("/")) { text, (theirs, ours) ->
                text.replace(theirs, ours)
            }
            return template.takeUnless { it.contains("{{") }
        }

    companion object {
        /** The names i18next-cli looks for, in its own order. */
        val FILE_NAMES = listOf("i18next.config.ts", "i18next.config.js", "i18next.config.mjs", "i18next.config.cjs")

        private val SCRIPT_EXTENSIONS = listOf("ts", "js", "mjs", "cjs")

        val NEXT_I18NEXT_FILE_NAMES = listOf("next-i18next.config.js", "next-i18next.config.cjs", "next-i18next.config.mjs")
        val LINGUI_FILE_NAMES = SCRIPT_EXTENSIONS.map { "lingui.config.$it" }
        val TRANSLOCO_FILE_NAMES = SCRIPT_EXTENSIONS.map { "transloco.config.$it" }

        /** Where a project usually calls i18next's `init`, project-relative. */
        val INIT_FILE_NAMES = listOf("ts", "js", "tsx", "jsx").flatMap { ext ->
            listOf("src/i18n.$ext", "src/i18n/index.$ext", "src/i18n/i18n.$ext", "src/i18n/config.$ext", "src/lib/i18n.$ext", "i18n.$ext")
        }

        /** Every file the setup wizard reads, the most explicit first. */
        val WIZARD_FILE_NAMES = FILE_NAMES + NEXT_I18NEXT_FILE_NAMES + LINGUI_FILE_NAMES + TRANSLOCO_FILE_NAMES + INIT_FILE_NAMES

        private const val PLACEHOLDER = "{"

        /** i18next's, i18next-cli's and Lingui's placeholders, to the plugin's. */
        private val PLACEHOLDERS = mapOf(
            "{{lng}}" to "{lang}",
            "{{language}}" to "{lang}",
            "{{ns}}" to "{ns}",
            "{{namespace}}" to "{ns}",
            "{locale}" to "{lang}",
            "{name}" to "{ns}",
        )
    }
}
