package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * What an i18next-cli configuration file (`i18next.config.ts`) states, as far as the setup wizard
 * can use it: the project already wrote down where its catalogues go and how its keys are split.
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
 * @param output `extract.output`, e.g. `public/locales/{{language}}/{{namespace}}.json`
 */
data class I18nextCliConfig(
    val file: String,
    val output: String? = null,
    val defaultNs: String? = null,
    val keySeparator: String? = null,
    val nsSeparator: String? = null,
) {

    /**
     * The folder [output] writes into, project-relative: its path up to the first segment holding
     * a placeholder, resolved from the configuration file's folder, which i18next-cli runs from.
     * Null when nothing fixed comes before the placeholders, or for an absolute path.
     */
    val translationsRoot: String?
        get() {
            val template = output?.replace('\\', '/')?.removePrefix("./") ?: return null
            if (template.startsWith("/")) return null
            val fixed = template.split('/').takeWhile { !it.contains(PLACEHOLDER) }
            // The last segment is the file name when no placeholder is met: `locales/en.json`.
            val folders = if (fixed.size == template.split('/').size) fixed.dropLast(1) else fixed
            val root = folders.filter { it.isNotEmpty() && it != "." }.joinToString("/")
            if (root.isEmpty()) return null
            val base = file.replace('\\', '/').substringBeforeLast('/', "")
            return if (base.isEmpty()) root else "$base/$root"
        }

    companion object {
        /** The names i18next-cli looks for, in its own order. */
        val FILE_NAMES = listOf("i18next.config.ts", "i18next.config.js", "i18next.config.mjs", "i18next.config.cjs")

        private const val PLACEHOLDER = "{{"
    }
}
